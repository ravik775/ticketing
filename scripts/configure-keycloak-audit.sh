#!/usr/bin/env bash
# Turns on Keycloak audit events for the application realm AND the master (admin) realm, logged by the
# jboss-logging listener and shipped to the SIEM (Loki), where detection rules alert on them
# (e.g. KeycloakMasterRealmLoginFailures, the compensating control for the accepted admin-console gap).
# Event details/representations are NOT stored (they could contain personal data). Idempotent.
#   Usage: scripts/configure-keycloak-audit.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1
KC() { kubectl -n auth exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config; }
ADMIN_PW="$(kubectl -n auth get secret keycloak-env -o jsonpath='{.data.KC_BOOTSTRAP_ADMIN_PASSWORD}' | base64 -d)"
KC config credentials --server http://localhost:8080/auth --realm master --user admin --password "$ADMIN_PW" >/dev/null 2>&1

for realm in ticketing master; do
  KC update "realms/$realm" \
    -s eventsEnabled=true \
    -s 'eventsListeners=["jboss-logging"]' \
    -s eventsExpiration=2592000 \
    -s adminEventsEnabled=true \
    -s adminEventsDetailsEnabled=false
  echo "realm $realm: $(KC get "realms/$realm" --fields eventsEnabled,adminEventsEnabled,eventsListeners | tr -d ' \n')"
done
