#!/usr/bin/env bash
# Gives a user a role in a tenant by adding them to the Keycloak group /<tenant>/<role>.
# Usage: scripts/add-role.sh <email-or-username> <tenant> <applicant|approver>
#   e.g. scripts/add-role.sh someone@gmail.com acme applicant
# Works for Google users too (they exist in Keycloak after their first sign-in).
# The user must sign out and in again: roles are read from the token.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash: keep /opt/keycloak/... a container path
[ $# -eq 3 ] || { echo "usage: $0 <email-or-username> <tenant> <applicant|approver>" >&2; exit 1; }
WHO="$1"; TENANT="$2"; ROLE="$3"
case "$ROLE" in applicant|approver) ;; *) echo "role must be applicant or approver" >&2; exit 1 ;; esac

ADMIN_PW="$(kubectl -n auth get secret keycloak-env -o jsonpath='{.data.KC_BOOTSTRAP_ADMIN_PASSWORD}' | base64 -d)"
KC="kubectl -n auth exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh"
CFG="--config /tmp/kcadm.config"
$KC config credentials --server http://localhost:8080/auth --realm master --user admin --password "$ADMIN_PW" $CFG >/dev/null

USER_ID="$($KC get users -r ticketing $CFG -q "email=$WHO" -q exact=true --fields id --format csv --noquotes)"
[ -n "$USER_ID" ] || USER_ID="$($KC get users -r ticketing $CFG -q "username=$WHO" -q exact=true --fields id --format csv --noquotes)"
[ -n "$USER_ID" ] || { echo "No user '$WHO' in realm ticketing (a Google user must sign in once first)" >&2; exit 1; }

GROUP_ID="$($KC get "group-by-path/$TENANT/$ROLE" -r ticketing $CFG --fields id --format csv --noquotes 2>/dev/null || true)"
[ -n "$GROUP_ID" ] || { echo "No group /$TENANT/$ROLE (tenants: acme, globex)" >&2; exit 1; }

$KC update "users/$USER_ID/groups/$GROUP_ID" -r ticketing $CFG -n
echo "'$WHO' is now $ROLE in $TENANT. Groups: $($KC get "users/$USER_ID/groups" -r ticketing $CFG --fields path --format csv --noquotes | tr '\n' ' ')"
echo "Sign out and sign in again for the new role to take effect."
