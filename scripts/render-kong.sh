#!/usr/bin/env bash
# Renders Kong's DB-less declarative config and loads it into the cluster as a Secret.
#   - JWT consumer: Keycloak's realm public key (Kong OSS has no OIDC plugin, so it verifies the
#     RS256 signature + expiry locally with the OSS "jwt" plugin)
#   - mTLS: Kong's client certificate (CN=kong-gateway) and the cluster CA, used toward upstreams
# Safe to re-run (e.g. after recreating the Keycloak realm); it restarts Kong to pick up changes.
set -euo pipefail

ISSUER="https://ticketing.localtest.me:8443/auth/realms/ticketing"
WORK="$(mktemp -d)"
trap 'kill "${PF_PID:-0}" 2>/dev/null || true; rm -rf "$WORK"' EXIT

echo "==> Waiting for cert-manager certificates"
kubectl wait --for=condition=Ready certificate --all -A --timeout=180s

secret_field() { kubectl -n "$1" get secret "$2" -o "jsonpath={.data.$3}" | base64 -d; }
indent() { local pad; pad="$(printf "%${1:-6}s" '')"; sed "s/^/${pad}/"; }   # [width], default 6

CLIENT_CERT="$(secret_field gateway kong-client-tls 'tls\.crt')"
CLIENT_KEY="$(secret_field gateway kong-client-tls 'tls\.key')"
CA_CERT="$(secret_field gateway kong-client-tls 'ca\.crt')"

echo "==> Waiting for Keycloak and reading the realm signing key"
kubectl -n auth rollout status deploy/keycloak --timeout=420s
# Keycloak's plain-HTTP port 8080 is reachable only from inside the pod / via port-forward.
kubectl -n auth port-forward deploy/keycloak 18080:8080 >/dev/null 2>&1 &
PF_PID=$!
PUBLIC_KEY=""
for _ in $(seq 1 90); do
  PUBLIC_KEY="$(curl -s --max-time 3 http://localhost:18080/auth/realms/ticketing 2>/dev/null \
    | sed -n 's/.*"public_key":"\([^"]*\)".*/\1/p' || true)"
  [ -n "$PUBLIC_KEY" ] && break
  sleep 3
done
[ -n "$PUBLIC_KEY" ] || { echo "Could not read the realm public key from Keycloak" >&2; exit 1; }
PEM="-----BEGIN PUBLIC KEY-----
$(printf '%s' "$PUBLIC_KEY" | fold -w 64)
-----END PUBLIC KEY-----"

cat > "$WORK/kong.yml" <<YAML
_format_version: "3.0"
_transform: true

certificates:
  - id: 6f1f0a10-0000-4000-8000-000000000001
    cert: |
$(printf '%s\n' "$CLIENT_CERT" | indent)
    key: |
$(printf '%s\n' "$CLIENT_KEY" | indent)

ca_certificates:
  - id: 6f1f0a10-0000-4000-8000-000000000002
    cert: |
$(printf '%s\n' "$CA_CERT" | indent)

plugins:
  - name: response-transformer
    config:
      add:
        headers:
          - "X-Content-Type-Options:nosniff"
          - "Strict-Transport-Security:max-age=31536000"

services:
  # ---- Backend API: JWT verified at the edge, then mTLS to the pod ----------------------------
  - name: ticket-service
    url: https://ticket-service.ticketing.svc.cluster.local:8443
    client_certificate:
      id: 6f1f0a10-0000-4000-8000-000000000001
    ca_certificates:
      - 6f1f0a10-0000-4000-8000-000000000002
    tls_verify: true
    routes:
      - name: api
        paths: ["/api"]
        strip_path: false
        protocols: ["https"]
        plugins:
          - name: jwt
            config:
              key_claim_name: iss
              claims_to_verify: ["exp"]
          # Per USER. The jwt plugin maps every Keycloak token to the single consumer below, so the
          # default limit_by=consumer would be one quota for everybody, and Kong OSS cannot key a
          # limit on a JWT claim. So pre-function copies the token's "sub" claim into a header the
          # gateway owns, and rate-limiting counts by that header. This is safe because of plugin order:
          #   pre-function (prio 1000000) -> jwt (1450, rejects unsigned/forged/expired) -> rate-limiting (910)
          # so only verified tokens are ever counted, and a client-sent X-Rate-Limit-Subject header is
          # always overwritten or removed. All tokens of one user (new logins, refreshes) share one quota.
          - name: pre-function
            config:
              access:
                - |
                  local sub
                  local auth = kong.request.get_header("authorization")
                  local payload = auth and auth:match("^[Bb]earer%s+[%w%-_]+%.([%w%-_]+)%.[%w%-_]+\$")
                  if payload then
                    payload = payload:gsub("%-", "+"):gsub("_", "/")
                    payload = payload .. string.rep("=", (4 - #payload % 4) % 4)
                    local claims = ngx.decode_base64(payload)
                    sub = claims and claims:match('"sub"%s*:%s*"([^"]+)"')
                  end
                  if sub then
                    kong.service.request.set_header("X-Rate-Limit-Subject", sub)
                  else
                    kong.service.request.clear_header("X-Rate-Limit-Subject")
                  end
          - name: rate-limiting
            config: {minute: 300, policy: local, limit_by: header, header_name: X-Rate-Limit-Subject}
          - name: request-size-limiting
            config: {allowed_payload_size: 1}
          - name: correlation-id
            config: {header_name: X-Correlation-ID, generator: uuid, echo_downstream: true}

  # ---- Identity provider (public: login pages, token + JWKS endpoints) -------------------------
  - name: keycloak
    url: https://keycloak.auth.svc.cluster.local:8443
    ca_certificates:
      - 6f1f0a10-0000-4000-8000-000000000002
    tls_verify: true
    routes:
      - name: auth
        paths: ["/auth"]
        strip_path: false
        protocols: ["https"]
        plugins:
          - name: rate-limiting
            config: {minute: 600, policy: local}

  # ---- Static UI ---------------------------------------------------------------------------------
  - name: ui
    url: http://ui.ticketing.svc.cluster.local:8080
    routes:
      - name: ui
        paths: ["/"]
        strip_path: false
        protocols: ["https"]

consumers:
  - username: keycloak-ticketing-realm
    jwt_secrets:
      - key: ${ISSUER}
        algorithm: RS256
        rsa_public_key: |
$(printf '%s\n' "$PEM" | indent 10)
YAML

echo "==> Loading config into Kong"
kubectl -n gateway create secret generic kong-declarative-config \
  --from-file=kong.yml="$WORK/kong.yml" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n gateway rollout restart deploy/kong
kubectl -n gateway rollout status deploy/kong --timeout=180s
echo "Kong is configured."
