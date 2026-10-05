#!/usr/bin/env bash
# Load test (k6 in Docker, nothing to install) through the real path WAF -> Kong -> service -> databases.
# Creates a temporary tenant `loadtest` (high quota, so the test measures the system, not the quota) with
# USERS applicants + USERS approvers, runs load/k6-ticketing.js, samples pod CPU/memory, then removes
# every test user, group, ticket and the tenant again (unless --keep).
#   Usage: scripts/load-test.sh [--keep]      env: USERS (default 10 per role)
set -uo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
USERS="${USERS:-10}"
POOL=$((2 * USERS))     # identities per role: one per k6 VU (see load/k6-ticketing.js)
PW="Lt-$(openssl rand -hex 12)"
KC() { kubectl -n auth exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config; }
PSQL() { kubectl -n ticketing exec postgres-0 -c postgres -- psql -U postgres -d ticketing -v ON_ERROR_STOP=1 -qtAc "$1"; }
KC config credentials --server http://localhost:8080/auth --realm master --user admin \
  --password "$(kubectl -n auth get secret keycloak-env -o jsonpath='{.data.KC_BOOTSTRAP_ADMIN_PASSWORD}' | base64 -d)" >/dev/null 2>&1

cleanup() {
  [ "${1:-}" = "--keep" ] && return 0
  echo "==> Cleaning up the loadtest tenant"
  for i in $(seq 1 "$POOL"); do for role in applicant approver; do
    id=$(KC get users -r ticketing -q "username=lt-$role-$i" -q exact=true --fields id --format csv --noquotes 2>/dev/null)
    [ -n "$id" ] && KC delete "users/$id" -r ticketing >/dev/null 2>&1
  done; done
  gid=$(KC get group-by-path/loadtest -r ticketing --fields id --format csv --noquotes 2>/dev/null); [ -n "$gid" ] && KC delete "groups/$gid" -r ticketing >/dev/null 2>&1
  PSQL "DELETE FROM ticket_outbox WHERE tenant_id='loadtest'; DELETE FROM ticket_workflow WHERE tenant_id='loadtest'; DELETE FROM tenant_usage WHERE tenant_id='loadtest'; DELETE FROM tenant WHERE id='loadtest';" \
    && echo "   PostgreSQL rows removed"
  kubectl -n ticketing exec mongo-0 -c mongo -- sh -c 'mongosh --quiet -u ticketing_app -p "$MONGO_APP_PASSWORD" --authenticationDatabase ticketing ticketing --eval "print(\"   MongoDB documents removed: \" + db.ticket_details.deleteMany({tenantId:\"loadtest\"}).deletedCount)"'
}

echo "==> Preparing tenant 'loadtest' with $USERS applicants and $USERS approvers"
PSQL "INSERT INTO tenant (id, name, tier, requests_per_minute, max_concurrent) VALUES ('loadtest','Load test','enterprise',100000,200)
      ON CONFLICT (id) DO UPDATE SET requests_per_minute=100000, max_concurrent=200, active=true;"
gid=$(KC get group-by-path/loadtest -r ticketing --fields id --format csv --noquotes 2>/dev/null)
if [ -z "$gid" ]; then
  gid=$(KC create groups -r ticketing -s name=loadtest -i)
  KC create "groups/$gid/children" -r ticketing -s name=applicant >/dev/null 2>&1
  KC create "groups/$gid/children" -r ticketing -s name=approver >/dev/null 2>&1
fi
for i in $(seq 1 "$POOL"); do for role in applicant approver; do
  u="lt-$role-$i"
  KC create users -r ticketing -s "username=$u" -s "email=$u@loadtest.test" -s emailVerified=true -s enabled=true \
     -s firstName=Load -s "lastName=$role$i" >/dev/null 2>&1
  KC set-password -r ticketing --username "$u" --new-password "$PW" >/dev/null 2>&1
  uid=$(KC get users -r ticketing -q "username=$u" -q exact=true --fields id --format csv --noquotes)
  rgid=$(KC get "group-by-path/loadtest/$role" -r ticketing --fields id --format csv --noquotes)
  KC update "users/$uid/groups/$rgid" -r ticketing -n >/dev/null 2>&1
done; done

echo "==> Sampling pod resource usage during the test (every 15 s)"
( for i in $(seq 1 12); do sleep 15; kubectl top pods -A --no-headers 2>/dev/null \
    | awk '$1 ~ /^(edge|gateway|auth|ticketing)$/ {printf "%s/%s %s %s\n",$1,$2,$3,$4}'; echo "--"; done ) > /tmp/load-top.txt 2>&1 &
SAMPLER=$!

echo "==> Running k6 (2m45s)"
W="$(pwd -W 2>/dev/null || pwd)/load"
docker run --rm -i --add-host ticketing.localtest.me:host-gateway -v "$W:/scripts:ro" \
  -e USERS="$USERS" -e LOAD_PASSWORD="$PW" grafana/k6:latest run --summary-trend-stats "avg,p(50),p(95),p(99),max" /scripts/k6-ticketing.js
RC=$?
wait "$SAMPLER" 2>/dev/null
echo "==> Peak CPU (millicores) and memory per pod during the test"
grep -v -- '--' /tmp/load-top.txt | sort -k1,1 -k2,2nr | awk '!seen[$1]++' | sort -k2 -nr | head -8

echo "==> Database view after the test"
PSQL "SELECT 'loadtest tickets: ' || count(*) || ', approved: ' || count(*) FILTER (WHERE status='APPROVED') FROM ticket_workflow WHERE tenant_id='loadtest';"
PSQL "SELECT 'outbox pending: ' || count(*) FROM ticket_outbox WHERE published_at IS NULL;"
cleanup "${1:-}"
exit $RC
