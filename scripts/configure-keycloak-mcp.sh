#!/usr/bin/env bash
# Registers the OAuth client used by MCP clients (AI agents) in the ticketing realm, from
# k8s/keycloak/client-ticketing-mcp.json: public client, Authorization Code + PKCE (S256), user consent
# required, no password grant, and an audience mapper so its access tokens carry aud=ticketing-api (the
# service rejects ticketing-mcp tokens without it). Idempotent: an existing client is left unchanged
# (delete it in the admin console first to re-create it from the file).
#   Usage: scripts/configure-keycloak-mcp.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
KCADM=(/opt/keycloak/bin/kcadm.sh)
KC() { kubectl -n auth exec deploy/keycloak -- "${KCADM[@]}" "$@" --config /tmp/kcadm.config; }
ADMIN_PW="$(kubectl -n auth get secret keycloak-env -o jsonpath='{.data.KC_BOOTSTRAP_ADMIN_PASSWORD}' | base64 -d)"
KC config credentials --server http://localhost:8080/auth --realm master --user admin --password "$ADMIN_PW" >/dev/null 2>&1

if KC get clients -r ticketing -q clientId=ticketing-mcp --fields clientId | grep -q '"ticketing-mcp"'; then
  echo "client ticketing-mcp: already registered"
else
  kubectl -n auth exec -i deploy/keycloak -- "${KCADM[@]}" create clients -r ticketing -f - \
    --config /tmp/kcadm.config < k8s/keycloak/client-ticketing-mcp.json
  echo "client ticketing-mcp: created"
fi
KC get clients -r ticketing -q clientId=ticketing-mcp \
  --fields clientId,publicClient,consentRequired,directAccessGrantsEnabled,redirectUris | tr -d ' \n'; echo
