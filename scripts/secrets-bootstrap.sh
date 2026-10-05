#!/usr/bin/env bash
# Secrets bootstrap for OpenBao (k8s/15-secrets-manager.yaml). Idempotent.
#
#   scripts/secrets-bootstrap.sh            initialise OpenBao if needed, configure it, and make sure every
#                                           credential exists in it (generated randomly; on a cluster that
#                                           predates OpenBao the CURRENT values are adopted so nothing breaks)
#   scripts/secrets-bootstrap.sh --rotate   generate NEW random credentials, apply them to PostgreSQL, MongoDB
#                                           and Keycloak, store them in OpenBao, re-sync and restart consumers
#   scripts/secrets-bootstrap.sh --sync     force External Secrets to refresh all synced Secrets now
#
# The OpenBao root token and recovery key are written to ~/.ticketing/openbao-init.json (mode 600, outside
# the repository). Keep that file safe; production uses KMS auto-unseal and no long-lived root token.
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
MODE="${1:-ensure}"
STATE_DIR="${TICKETING_STATE_DIR:-$HOME/.ticketing}"
INIT="$STATE_DIR/openbao-init.json"
mkdir -p "$STATE_DIR"; chmod 700 "$STATE_DIR" 2>/dev/null || true

rand() { openssl rand -base64 64 | tr -dc 'A-Za-z0-9' | head -c "${1:-32}"; }
secret_val() { kubectl -n "$1" get secret "$2" -o go-template="{{ index .data \"$3\" }}" 2>/dev/null | base64 -d 2>/dev/null || true; }
managed_by_eso() { kubectl -n "$1" get secret "$2" -o jsonpath='{.metadata.ownerReferences[0].kind}' 2>/dev/null | grep -q ExternalSecret; }

force_sync() {
  for ns in ticketing auth observability; do
    for es in $(kubectl -n "$ns" get externalsecrets -o name 2>/dev/null); do
      kubectl -n "$ns" annotate "$es" force-sync="$(date +%s)" --overwrite >/dev/null
    done
  done
  for i in $(seq 1 30); do
    notready=$(kubectl get externalsecrets -A --no-headers 2>/dev/null | awk '$NF!="True"' | wc -l)
    [ "$notready" -eq 0 ] && break; sleep 2
  done
  kubectl get externalsecrets -A
}
if [ "$MODE" = "--sync" ]; then force_sync; exit 0; fi

# ---------------------------------------------------------------- 1. unseal key + server running
if ! kubectl -n secrets get secret openbao-unseal >/dev/null 2>&1; then
  kubectl -n secrets create secret generic openbao-unseal --from-literal=key="$(openssl rand -hex 32)" >/dev/null
  echo "created static unseal key (Kubernetes Secret secrets/openbao-unseal; production: AWS KMS)"
fi
kubectl -n secrets rollout status statefulset/openbao --timeout=300s >/dev/null 2>&1 || true
for i in $(seq 1 60); do
  kubectl -n secrets get pod openbao-0 -o jsonpath='{.status.containerStatuses[0].started}' 2>/dev/null | grep -q true && break; sleep 3
done

bao_status() { kubectl -n secrets exec openbao-0 -c openbao -- bao status -format=json 2>/dev/null || true; }
if bao_status | grep -q '"initialized": false'; then
  kubectl -n secrets exec openbao-0 -c openbao -- bao operator init -format=json > "$INIT"
  chmod 600 "$INIT"
  echo "OpenBao initialised; root token + recovery key saved to $INIT (keep it safe, never commit it)"
fi
[ -s "$INIT" ] || { echo "OpenBao is initialised but $INIT is missing: cannot administer it." >&2; exit 1; }
ROOT=$(grep -o '"root_token": *"[^"]*"' "$INIT" | cut -d'"' -f4)
for i in $(seq 1 30); do bao_status | grep -q '"sealed": false' && break; sleep 2; done

# Runs a shell script inside the OpenBao pod with the root token (passed on stdin, never on a command line).
bao_sh() { { printf '%s\n' "$ROOT"; cat; } | kubectl -n secrets exec -i openbao-0 -c openbao -- sh -c 'read -r BAO_TOKEN; export BAO_TOKEN; sh -s'; }

# ---------------------------------------------------------------- 2. configuration (idempotent)
bao_sh <<'EOS'
set -e
bao secrets list | grep -q '^secret/' || bao secrets enable -path=secret -version=2 kv
bao auth list | grep -q '^kubernetes/' || bao auth enable kubernetes
bao write auth/kubernetes/config kubernetes_host=https://kubernetes.default.svc:443 >/dev/null
for ns in ticketing auth observability; do
  case "$ns" in
    ticketing)     extra='path "secret/data/shared/keycloak-db" { capabilities = ["read"] }' ;;
    auth)          extra='path "secret/data/shared/keycloak-db" { capabilities = ["read"] }' ;;
    observability) extra='' ;;
  esac
  printf 'path "secret/data/%s/*" { capabilities = ["read"] }\n%s\n' "$ns" "$extra" | bao policy write "$ns" - >/dev/null
  bao write "auth/kubernetes/role/$ns" bound_service_account_names=secrets-reader \
      bound_service_account_namespaces="$ns" policies="$ns" ttl=15m >/dev/null
done
echo "OpenBao configured: kv-v2 at secret/, Kubernetes auth roles (ticketing, auth, observability), audit to stdout"
EOS

# ---------------------------------------------------------------- 3. credentials
kv_get() { bao_sh <<EOS 2>/dev/null || true
bao kv get -mount=secret -field="$2" "$1"
EOS
}
kv_put() { local path="$1"; shift; printf 'bao kv put -mount=secret %s %s >/dev/null\n' "$path" "$*" | bao_sh; }

if [ "$MODE" = "--rotate" ]; then
  echo "==> Rotating all infrastructure credentials"
  OLD_MONGO_ROOT=$(secret_val ticketing mongo-credentials MONGO_INITDB_ROOT_PASSWORD)
  OLD_KC_ADMIN=$(secret_val auth keycloak-env KC_BOOTSTRAP_ADMIN_PASSWORD)
  PG_SUPER=$(rand); PG_APP=$(rand); KC_DB=$(rand); MONGO_ROOT=$(rand); MONGO_APP=$(rand); KC_ADMIN=$(rand); GRAFANA=$(rand)
  # 1. databases and Keycloak accept the new values (old sessions keep working until restart)
  kubectl -n ticketing exec postgres-0 -c postgres -- psql -U postgres -v ON_ERROR_STOP=1 -q \
    -c "ALTER ROLE postgres PASSWORD '$PG_SUPER'" -c "ALTER ROLE ticketing_app PASSWORD '$PG_APP'" -c "ALTER ROLE keycloak PASSWORD '$KC_DB'"
  kubectl -n ticketing exec mongo-0 -c mongo -- mongosh --quiet -u mongoadmin -p "$OLD_MONGO_ROOT" --authenticationDatabase admin --eval \
    "db.getSiblingDB('ticketing').changeUserPassword('ticketing_app', '$MONGO_APP'); db.getSiblingDB('admin').changeUserPassword('mongoadmin', '$MONGO_ROOT'); print('mongo passwords changed')"
  kubectl -n auth exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080/auth --realm master \
    --user admin --password "$OLD_KC_ADMIN" --config /tmp/kcadm-rotate.config >/dev/null
  kubectl -n auth exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh set-password -r master --username admin --new-password "$KC_ADMIN" --config /tmp/kcadm-rotate.config
  kubectl -n auth exec deploy/keycloak -- rm -f /tmp/kcadm-rotate.config /tmp/kcadm.config
  # 2. the secrets manager becomes the source of truth
  kv_put ticketing/postgres "superuser_password=$PG_SUPER" "app_password=$PG_APP"
  kv_put shared/keycloak-db "password=$KC_DB"
  kv_put ticketing/mongo "root_password=$MONGO_ROOT" "app_password=$MONGO_APP"
  kv_put auth/keycloak-admin "password=$KC_ADMIN"
  kv_put observability/grafana "admin_password=$GRAFANA"
else
  # ensure: create missing credentials; adopt current values if the cluster predates OpenBao
  ensure() {  # path property legacy-namespace legacy-secret legacy-key
    if [ -z "$(kv_get "$1" "$2")" ]; then
      local v; v=$(secret_val "$3" "$4" "$5")
      if [ -n "$v" ] && ! managed_by_eso "$3" "$4"; then echo "adopting current $1.$2 from $3/$4" >&2; else v=$(rand); echo "generated $1.$2" >&2; fi
      printf '%s' "$v"
    fi
  }
  add() {     # path "prop=value" ... (only the missing properties are passed; existing ones are kept)
    local path="$1"; shift; [ $# -eq 0 ] && return 0
    if printf 'bao kv get -mount=secret %s >/dev/null 2>&1\n' "$path" | bao_sh 2>/dev/null; then
      printf 'bao kv patch -mount=secret %s %s >/dev/null\n' "$path" "$*" | bao_sh       # keep existing properties
    else
      kv_put "$path" "$@"
    fi
  }
  v=$(ensure ticketing/postgres superuser_password ticketing postgres-credentials POSTGRES_PASSWORD | tail -1); [ -n "$v" ] && add ticketing/postgres "superuser_password=$v"
  v=$(ensure ticketing/postgres app_password ticketing postgres-credentials TICKETING_DB_PASSWORD | tail -1); [ -n "$v" ] && add ticketing/postgres "app_password=$v"
  v=$(ensure shared/keycloak-db password ticketing postgres-credentials KEYCLOAK_DB_PASSWORD | tail -1); [ -n "$v" ] && add shared/keycloak-db "password=$v"
  v=$(ensure ticketing/mongo root_password ticketing mongo-credentials MONGO_INITDB_ROOT_PASSWORD | tail -1); [ -n "$v" ] && add ticketing/mongo "root_password=$v"
  v=$(ensure ticketing/mongo app_password ticketing mongo-credentials MONGO_APP_PASSWORD | tail -1); [ -n "$v" ] && add ticketing/mongo "app_password=$v"
  v=$(ensure ticketing/mongo-keyfile key ticketing mongo-keyfile mongo.key | tail -1)
  if [ -n "$v" ]; then
    if [ ${#v} -lt 100 ]; then v=$(openssl rand -base64 756 | tr -d '\n'); fi    # a key file needs >= 6 chars; use 756 random bytes
    add ticketing/mongo-keyfile "key=$v"
  fi
  v=$(ensure auth/keycloak-admin password auth keycloak-env KC_BOOTSTRAP_ADMIN_PASSWORD | tail -1); [ -n "$v" ] && add auth/keycloak-admin "password=$v"
  v=$(ensure observability/grafana admin_password observability grafana-admin password | tail -1); [ -n "$v" ] && add observability/grafana "admin_password=$v"
fi

# ---------------------------------------------------------------- 4. hand over to External Secrets
# Secrets created before OpenBao (by kustomize) are replaced by ESO-owned ones with the same name.
if kubectl get crd externalsecrets.external-secrets.io >/dev/null 2>&1 && kubectl get externalsecrets -A --no-headers 2>/dev/null | grep -q .; then
  for s in ticketing/postgres-credentials ticketing/mongo-credentials ticketing/mongo-keyfile ticketing/ticket-service-env auth/keycloak-env observability/grafana-admin; do
    ns=${s%/*}; name=${s#*/}
    if kubectl -n "$ns" get secret "$name" >/dev/null 2>&1 && ! managed_by_eso "$ns" "$name"; then
      kubectl -n "$ns" delete secret "$name" >/dev/null && echo "handed $s over to External Secrets"
    fi
  done
  force_sync
fi

if [ "$MODE" = "--rotate" ]; then
  echo "==> Restarting consumers so they use the new credentials"
  kubectl -n ticketing rollout restart deploy/ticket-service statefulset/mongo >/dev/null
  kubectl -n auth rollout restart deploy/keycloak >/dev/null
  kubectl -n observability rollout restart deploy/grafana >/dev/null
  kubectl -n ticketing rollout status statefulset/mongo --timeout=300s
  kubectl -n auth rollout status deploy/keycloak --timeout=420s
  kubectl -n ticketing rollout status deploy/ticket-service --timeout=300s
  kubectl -n observability rollout status deploy/grafana --timeout=300s
fi
echo "Secrets bootstrap done ($MODE)."
