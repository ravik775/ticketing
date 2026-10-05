#!/usr/bin/env bash
# Proves the observability + SIEM pipeline works end to end (not just that the pods run):
#   metrics scraped, SLO/alert rules loaded, a request produces metrics + a trace in Tempo, logs and the
#   Kubernetes audit log arrive in Loki, and a security detection really FIRES and reaches Alertmanager.
#   Usage: scripts/verify-observability.sh [--skip-alert-test]
set -uo pipefail
export MSYS_NO_PATHCONV=1
B="${BASE:-https://ticketing.localtest.me:8443}"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "PASS  $1"; }
bad()  { FAIL=$((FAIL+1)); echo "FAIL  $1"; }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
# All queries run from inside the Prometheus pod (observability namespace), so nothing is exposed.
get() { kubectl -n observability exec deploy/prometheus -- wget -qO- "$1" 2>/dev/null; }
prom() { get "http://localhost:9090/api/v1/query?query=$1"; }
loki() { get "http://loki.observability.svc.cluster.local:3100/loki/api/v1/query_range?limit=5&since=1h&query=$1"; }

echo "== Metrics"
up=$(prom 'count(up==1)' | grep -oE '"[0-9]+"\]' | tr -d '"]'); total=$(prom 'count(up)' | grep -oE '"[0-9]+"\]' | tr -d '"]')
check "all scrape targets are up ($up/$total)" '[ -n "$up" ] && [ "$up" = "$total" ]'
rules=$(get 'http://localhost:9090/api/v1/rules' | grep -oE '"type":"alerting"' | wc -l)
check "Prometheus alerting rules loaded ($rules)" '[ "$rules" -ge 12 ]'

echo "== A request is visible as metrics and as a trace"
TOK=$(curl -sk "$B/auth/realms/ticketing/protocol/openid-connect/token" -d grant_type=password -d client_id=ticketing-ui \
      -d username=bob -d 'password=Passw0rd!' -d scope=openid | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)
for i in 1 2 3 4 5; do curl -sk -o /dev/null -H "Authorization: Bearer $TOK" "$B/api/approvals/tickets?size=5"; done
sleep 40   # one scrape interval + trace batching
check "Kong request metrics (kong_http_requests_total)" '[ -n "$(prom "sum(kong_http_requests_total{service=\"ticket-service\"})" | grep -oE "\"value\":\[[^]]*\]")" ]'
check "service latency histogram with SLO bucket le=1.0" 'prom "count(http_server_requests_seconds_bucket{le=\"1.0\"})" | grep -q "\"value\""'
check "outbox gauge exported" 'prom "ticketing_outbox_pending" | grep -q "\"value\""'
check "per-tenant request metering exported" 'prom "ticketing_tenant_requests_total" | grep -q "\"tenant\":\"acme\""'
check "trace from Kong in Tempo" 'get "http://tempo.observability.svc.cluster.local:3200/api/search?tags=service.name%3Dkong&limit=3" | grep -q "traceID"'
check "trace from ticket-service in Tempo" 'get "http://tempo.observability.svc.cluster.local:3200/api/search?tags=service.name%3Dticket-service&limit=3" | grep -q "traceID"'

echo "== Logs and audit (local SIEM)"
check "pod logs in Loki (WAF access log)" 'loki "%7Bnamespace%3D%22edge%22%7D" | grep -q "\"values\""'
check "Kubernetes API audit events in Loki" 'loki "%7Bjob%3D%22kubernetes-audit%22%7D" | grep -q "\"values\""'
check "Keycloak login events in Loki" 'loki "%7Bnamespace%3D%22auth%22%7D%20%7C%3D%20%22type%3D%5C%22LOGIN%22" | grep -q "\"values\""'
check "backup heartbeat in Loki" 'loki "%7Bcontainer%3D%22backup%22%7D%20%7C%3D%20%22backup%20status%22" | grep -q "\"values\""'
lrules=$(get 'http://loki.observability.svc.cluster.local:3100/prometheus/api/v1/rules' | grep -oE '"type":"alerting"' | wc -l)
check "Loki security detection rules loaded ($lrules)" '[ "$lrules" -ge 10 ]'

echo "== Alerting"
check "Watchdog (dead man's switch) is firing in Alertmanager" 'get "http://alertmanager.observability.svc.cluster.local:9093/api/v2/alerts" | grep -q "\"alertname\":\"Watchdog\""'
if [ "${1:-}" != "--skip-alert-test" ]; then
  # The admin realm is blocked at the public edge (WAF + Kong), so the attack is simulated from the
  # internal admin path (port-forward), where an insider or a compromised workstation would come from.
  echo "      simulating an attack on the admin (master) realm via the internal admin path: 7 failed logins..."
  kubectl -n auth port-forward svc/keycloak 19443:8443 >/dev/null 2>&1 & PF=$!; sleep 4
  for i in $(seq 1 7); do
    curl -sk -o /dev/null "https://localhost:19443/auth/realms/master/protocol/openid-connect/token" -d grant_type=password -d client_id=admin-cli -d username=admin -d "password=wrong-$i"
  done
  kill "$PF" 2>/dev/null || true
  fired=no
  for i in $(seq 1 24); do   # Loki ruler evaluates every minute; allow up to 4 minutes
    if get "http://alertmanager.observability.svc.cluster.local:9093/api/v2/alerts" | grep -q '"alertname":"KeycloakMasterRealmLoginFailures"'; then fired=yes; break; fi
    sleep 10
  done
  check "detection KeycloakMasterRealmLoginFailures fired and reached Alertmanager" '[ "$fired" = yes ]'
  # The failed attempts also trigger Keycloak brute-force protection for 'admin'; clear it so admin work is not blocked.
  KC() { kubectl -n auth exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config; }
  KC config credentials --server http://localhost:8080/auth --realm master --user admin \
    --password "$(kubectl -n auth get secret keycloak-env -o jsonpath='{.data.KC_BOOTSTRAP_ADMIN_PASSWORD}' | base64 -d)" >/dev/null 2>&1 \
    && KC delete attack-detection/brute-force/users -r master >/dev/null 2>&1 || true
fi

echo; echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
