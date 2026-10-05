#!/usr/bin/env bash
# End-to-end checks against the running cluster, through Kong. Prints PASS/FAIL per scenario.
set -uo pipefail

BASE="${BASE:-https://ticketing.localtest.me:8443}"
OUT="$(mktemp)"; trap 'rm -f "$OUT"; kill ${PF_PID:-0} 2>/dev/null || true' EXIT
PASS=0; FAIL=0; STATUS=""

token() {  # user [field] -> access token, or another token field (password grant is enabled for the demo client only for this script)
  curl -sk --max-time 20 "$BASE/auth/realms/ticketing/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=ticketing-ui -d "username=$1" -d 'password=Passw0rd!' -d scope=openid \
    | grep -o "\"${2:-access_token}\":\"[^\"]*\"" | cut -d'"' -f4
}
call() {   # method path token [tenant] [json-body]
  local args=(-sk --max-time 20 -o "$OUT" -w '%{http_code}' -X "$1")
  [ -n "${3:-}" ] && args+=(-H "Authorization: Bearer $3")
  [ -n "${4:-}" ] && args+=(-H "X-Tenant-ID: $4")
  [ -n "${5:-}" ] && args+=(-H 'Content-Type: application/json' -d "$5")
  STATUS="$(curl "${args[@]}" "$BASE$2")"
}
field() { grep -o "\"$1\":\"[^\"]*\"" "$OUT" | head -1 | cut -d'"' -f4; }
expect() { # description wanted-status
  if [ "$STATUS" = "$2" ]; then PASS=$((PASS+1)); echo "PASS  $1"; else FAIL=$((FAIL+1)); echo "FAIL  $1 (wanted $2, got $STATUS)"; cat "$OUT"; echo; fi
}
expect_body() { # description grep-pattern
  if grep -q "$2" "$OUT"; then PASS=$((PASS+1)); echo "PASS  $1"; else FAIL=$((FAIL+1)); echo "FAIL  $1 (pattern '$2' not in response)"; cat "$OUT"; echo; fi
}
expect_absent() {
  if grep -q "$2" "$OUT"; then FAIL=$((FAIL+1)); echo "FAIL  $1"; else PASS=$((PASS+1)); echo "PASS  $1"; fi
}

echo "== Getting tokens"
ALICE=$(token alice); ERIN=$(token erin); BOB=$(token bob); CAROL=$(token carol); DAVE=$(token dave)
for t in "$ALICE" "$ERIN" "$BOB" "$CAROL" "$DAVE"; do
  [ -n "$t" ] || { echo "Could not obtain tokens - is the cluster up (scripts/up.sh)?"; exit 2; }
done
NEW='{"title":"VPN broken","mobile":"+919876543210","description":"Cannot connect","createdBy":"evil@x.com","tenantId":"globex"}'

echo; echo "== Authentication and tenant selection"
call GET /api/tickets "";                       expect "no token is rejected at the gateway" 401
call GET /api/me "$ALICE";                      expect "/api/me works without choosing a tenant" 200
call POST /api/tickets "$ALICE" "" "$NEW";      expect "multi-tenant user must pick a tenant" 400
call POST /api/tickets "$ALICE" initech "$NEW"; expect "tenant not proven by the token is refused" 403
call GET /api/me "$(token alice id_token)";    expect "an ID token is not accepted as an access token" 401

echo; echo "== Rate limit is per user (JWT sub), not per token or shared"
remaining() { curl -sk --max-time 20 -o /dev/null -D - -H "Authorization: Bearer $1" "$BASE/api/me" \
  | tr -d '\r' | grep -i '^x-ratelimit-remaining-minute:' | awk '{print $2}'; }
ALICE2=$(token alice)
A1=$(remaining "$ALICE"); A2=$(remaining "$ALICE2"); B1=$(remaining "$BOB"); B2=$(remaining "$BOB"); A3=$(remaining "$ALICE")
echo "      alice token1=$A1 token2=$A2, bob=$B1,$B2, alice again=$A3"
STATUS=$([ -n "$A1" ] && [ "$A2" = "$((A1-1))" ] && echo same || echo different)
expect "two tokens of the same user draw from one quota" same
STATUS=$([ -n "$B1" ] && [ "$B2" = "$((B1-1))" ] && [ "$A3" = "$((A2-1))" ] && echo separate || echo shared)
expect "another user's calls do not consume this user's quota" separate
B3="$(curl -sk --max-time 20 -o /dev/null -D - -H "Authorization: Bearer $BOB" -H "X-Rate-Limit-Subject: someone-else" \
  "$BASE/api/me" | tr -d '\r' | grep -i '^x-ratelimit-remaining-minute:' | awk '{print $2}')"
STATUS=$([ -n "$B3" ] && [ "$B3" = "$((B2-1))" ] && echo ignored || echo honoured)
expect "a client-supplied subject header is ignored" ignored

echo; echo "== Applicant"
call POST /api/tickets "$ALICE" acme "$NEW";    expect "applicant raises a ticket in acme" 201
ID="$(field id)"
expect_body "tenant comes from security context, not the body" '"tenantId":"acme"'
expect_body "email comes from security context, not the body" '"createdBy":"alice@ticketing.test"'
call GET /api/tickets "$ALICE" acme;            expect "applicant lists own tickets" 200
expect_body "own ticket is listed" "$ID"
call GET /api/tickets "$ALICE" globex;          expect "same user in another tenant sees a separate (empty) list" 200
expect_absent "ticket is invisible in the other tenant" "$ID"
call GET "/api/tickets/$ID" "$ERIN";            expect "another applicant cannot open it (404, no leak)" 404
call GET /api/tickets "$ERIN";                  expect "another applicant's list" 200
expect_absent "another applicant does not see it in their list" "$ID"
call POST /api/tickets "$ALICE" acme '{"title":"x","mobile":"abc","description":"d"}'; expect "invalid mobile number is rejected" 400

echo; echo "== Approver rules"
call POST /api/tickets "$BOB" "" "$NEW";        expect "approver cannot raise a ticket" 403
call GET /api/approvals/tickets "$ALICE" acme;  expect "applicant cannot use approver API" 403
call GET /api/approvals/tickets "$BOB";         expect "approver lists tenant tickets (tenant inferred)" 200
expect_body "approver sees tickets raised by users in the tenant" "$ID"
call GET "/api/approvals/tickets/$ID" "$DAVE";  expect "approver of another tenant cannot see it (tenant isolation)" 404
call POST "/api/approvals/tickets/$ID/decision" "$BOB" "" '{"decision":"APPROVE","comment":"x"}'
expect "decision without picking up is refused" 409

echo; echo "== Locking"
call POST "/api/approvals/tickets/$ID/claim" "$BOB";   expect "bob picks the ticket up (locks it)" 200
expect_body "ticket shows who holds the lock" '"lockedBy":"bob@ticketing.test"'
call POST "/api/approvals/tickets/$ID/claim" "$CAROL"; expect "carol cannot pick up a locked ticket" 409
call POST "/api/approvals/tickets/$ID/decision" "$CAROL" "" '{"decision":"APPROVE","comment":"x"}'
expect "carol cannot decide a ticket locked by bob" 409
call POST "/api/approvals/tickets/$ID/unlock" "$CAROL"; expect "carol unlocks it" 200
call POST "/api/approvals/tickets/$ID/claim" "$CAROL"; expect "carol picks it up" 200
call POST "/api/approvals/tickets/$ID/decision" "$CAROL" "" '{"decision":"APPROVE","comment":"Looks good"}'
expect "carol approves" 200
expect_body "ticket is approved" '"status":"APPROVED"'
call GET "/api/tickets/$ID" "$ALICE" acme;      expect "applicant sees the outcome" 200
expect_body "approver comment is visible to the applicant" 'Looks good'

echo; echo "== Request more details"
call POST /api/tickets "$ALICE" acme "$NEW"; ID2="$(field id)"
call POST "/api/approvals/tickets/$ID2/claim" "$BOB"; expect "claim second ticket" 200
call POST "/api/approvals/tickets/$ID2/decision" "$BOB" "" '{"decision":"REQUEST_INFO","comment":"Which server?"}'
expect "approver requests more details" 200
call POST "/api/tickets/$ID2/respond" "$ALICE" acme '{"comment":"The VPN gateway"}'
expect "applicant answers" 200
expect_body "ticket is back in the approvers' queue" '"status":"OPEN"'

echo; echo "== Web Application Firewall (ModSecurity + OWASP CRS in front of Kong)"
waf() { STATUS="$(curl -sk --max-time 20 -o "$OUT" -w '%{http_code}' "$@")"; }
waf "$BASE/api/tickets?status=1%27%20OR%20%271%27=%271" -H "Authorization: Bearer $BOB"
expect "SQL injection in a query parameter is blocked" 403
waf -X POST "$BASE/api/tickets" -H "Authorization: Bearer $ALICE" -H 'X-Tenant-ID: acme' -H 'Content-Type: application/json' \
  -d '{"title":"<script>alert(1)</script>","mobile":"+919876543210","description":"x"}'
expect "cross-site scripting in a JSON body is blocked" 403
waf -A "sqlmap/1.7" "$BASE/";                       expect "known attack-tool user agent is blocked" 403
waf "$BASE/.git/config";                            expect "path outside the application is refused" 404
waf -X PUT "$BASE/api/tickets" -H "Authorization: Bearer $ALICE"
expect "HTTP method the API does not use is blocked" 403
waf -X POST "$BASE/api/tickets" -H "Authorization: Bearer $ALICE" -H 'Content-Type: text/plain' -d x
expect "non-JSON body to the API is refused" 415
waf -H 'Host: evil.example' "$BASE/";               expect "request for an unknown hostname is refused" 421
waf -I "$BASE/";                                    expect_body "responses do not reveal the proxy software version" 'erver: waf'

echo; echo "== Zero Trust / network (needs kubectl access to the cluster)"
if command -v kubectl >/dev/null; then
  kubectl -n ticketing port-forward deploy/ticket-service 18443:8443 >/dev/null 2>&1 & PF_PID=$!
  sleep 4
  code="$(curl -sk --max-time 8 -o /dev/null -w '%{http_code}' https://localhost:18443/api/me 2>/dev/null)"; code="${code:-000}"
  STATUS="$code"; expect "service refuses a TLS client without a certificate (mTLS)" 000
  CRT="$(mktemp)"; KEY="$(mktemp)"
  kubectl -n gateway get secret kong-client-tls -o 'jsonpath={.data.tls\.crt}' | base64 -d > "$CRT"
  kubectl -n gateway get secret kong-client-tls -o 'jsonpath={.data.tls\.key}' | base64 -d > "$KEY"
  mtls_status() {  # [token] -> HTTP status of GET /api/me presenting Kong's client certificate
    if curl -V | head -1 | grep -q OpenSSL; then
      curl -sk --max-time 8 --cert "$CRT" --key "$KEY" ${1:+-H "Authorization: Bearer $1"} -o "$OUT" -w '%{http_code}' https://localhost:18443/api/me
    else  # e.g. Git for Windows curl (Schannel) cannot load PEM client certificates
      printf 'GET /api/me HTTP/1.1\r\nHost: localhost\r\n%sConnection: close\r\n\r\n' "${1:+Authorization: Bearer $1$'\r\n'}" \
        | timeout 10 openssl s_client -quiet -connect localhost:18443 -cert "$CRT" -key "$KEY" 2>/dev/null \
        | head -1 | awk '{print $2}'
    fi
  }
  STATUS="$(mtls_status)"; STATUS="${STATUS:-000}"
  expect "valid gateway certificate alone is not enough: JWT still required" 401
  STATUS="$(mtls_status "$BOB")"; STATUS="${STATUS:-000}"
  expect "gateway certificate + valid JWT is accepted by the service itself" 200
  rm -f "$CRT" "$KEY"

  for target in "ticketing|https://ticket-service.ticketing.svc.cluster.local:8443/api/me" \
                "gateway|https://keycloak.auth.svc.cluster.local:8443/auth/" \
                "ticketing|https://kong.gateway.svc.cluster.local/api/me"; do
    ns="${target%%|*}"; url="${target#*|}"
    res="$(kubectl -n "$ns" run "rogue-$RANDOM" --rm -i --restart=Never --pod-running-timeout=90s \
           --image=curlimages/curl:8.10.1 --command -- curl -sk -m 6 -o /dev/null -w 'HTTP:%{http_code}' "$url" 2>/dev/null)"
    STATUS="$(printf '%s' "$res" | grep -o 'HTTP:[0-9]*' | head -1 | cut -d: -f2)"; STATUS="${STATUS:-000}"
    expect "unlabelled pod in '$ns' is blocked by NetworkPolicy from $url" 000
  done
else
  echo "SKIP  kubectl not found"
fi

echo; echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
