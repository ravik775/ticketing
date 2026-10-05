#!/usr/bin/env bash
# Enables "Sign in with Google" in the Keycloak realm.
# Usage: scripts/set-google.sh <google-client-id> <google-client-secret>
# In the Google Cloud console create an OAuth client (type: Web) and add this redirect URI:
#   https://ticketing.localtest.me:8443/auth/realms/ticketing/broker/google/endpoint
# Google users are created with NO tenant: an administrator adds them to a group such as /acme/applicant
# (Keycloak admin console: https://ticketing.localtest.me:8443/auth/admin, user "admin").
set -euo pipefail
# Git Bash (Windows) would otherwise rewrite /opt/keycloak/... into a Windows path before kubectl exec.
export MSYS_NO_PATHCONV=1
[ $# -eq 2 ] || { echo "usage: $0 <client-id> <client-secret>" >&2; exit 1; }
ID="$1"; SECRET="$2"
ADMIN_PW="$(kubectl -n auth get secret keycloak-env -o jsonpath='{.data.KC_BOOTSTRAP_ADMIN_PASSWORD}' | base64 -d)"
KC="kubectl -n auth exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh"
CFG="--config /tmp/kcadm.config"

$KC config credentials --server http://localhost:8080/auth --realm master --user admin --password "$ADMIN_PW" $CFG
if $KC get identity-provider/instances/google -r ticketing $CFG >/dev/null 2>&1; then
  $KC update identity-provider/instances/google -r ticketing $CFG \
    -s enabled=true -s config.clientId="$ID" -s config.clientSecret="$SECRET"
else
  $KC create identity-provider/instances -r ticketing $CFG \
    -s alias=google -s providerId=google -s enabled=true -s trustEmail=true \
    -s config.clientId="$ID" -s config.clientSecret="$SECRET" -s 'config.defaultScope=openid email profile'
fi

# Verify: "Sign in with Google" must now redirect straight to Google, not to Keycloak's password form.
BASE="https://ticketing.localtest.me:8443"
JAR="$(mktemp)"; trap 'rm -f "$JAR"' EXIT
TARGET="$(curl -sk -L --max-redirs 3 -c "$JAR" -b "$JAR" -o /dev/null -w '%{url_effective} %{redirect_url}' \
  --get "$BASE/auth/realms/ticketing/protocol/openid-connect/auth" \
  --data-urlencode client_id=ticketing-ui --data-urlencode "redirect_uri=$BASE/" --data-urlencode response_type=code \
  --data-urlencode scope=openid --data-urlencode kc_idp_hint=google --data-urlencode code_challenge_method=S256 \
  --data-urlencode code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM || true)"   # Google redirects further: hitting --max-redirs is expected
case "$TARGET" in
  *https://accounts.google.com/*) echo "Google sign-in enabled: the login button now redirects to accounts.google.com." ;;
  *) echo "Google provider saved, but the sign-in redirect went to: ${TARGET:-<none>}" >&2; exit 1 ;;
esac
