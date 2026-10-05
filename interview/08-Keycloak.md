# Keycloak: identity, OAuth 2.0 and OpenID Connect

## Concepts you must own

* **OAuth 2.0** is *delegated authorisation* (access tokens for APIs); **OpenID Connect** adds
  *authentication* on top (ID token, `userinfo`, discovery). Access token = for the API; ID token = for
  the client to know who logged in, **never** sent to APIs.
* **Flows:** Authorization Code (+ **PKCE**) for browsers/mobile; Client Credentials for
  service-to-service; Device Code for input-constrained devices; Implicit and Resource Owner Password
  are deprecated (OAuth 2.1 removes them).
* **Clients:** public (cannot keep a secret: SPA, mobile → PKCE mandatory) vs confidential (server
  side, authenticates with secret/private-key JWT/mTLS).
* **Tokens:** JWT (header.payload.signature), signed RS256 with realm keys published at JWKS
  (`/protocol/openid-connect/certs`), identified by `kid`. Claims: `iss`, `sub`, `aud`, `azp`, `exp`,
  `iat`, `typ`, `scope`, custom (`groups`).
* **Realm** = isolated tenant of Keycloak (users, clients, keys). `master` = administration only.
* **Client scopes & mappers** decide what goes into tokens. In Keycloak 25+, `sub` comes from the
  `basic` scope.
* **Identity brokering:** Keycloak as a relying party to external IdPs (Google); *first broker login*
  flow creates/links local accounts.
* **Sessions:** SSO session (cookie at Keycloak) vs token lifetimes; refresh tokens extend access
  without re-login; idle and max timeouts.

## How this application applies them

| Concept | Implementation | Where |
|---|---|---|
| Browser flow | Auth Code + PKCE S256, public client `ticketing-ui`, no implicit | `k8s/keycloak/realm-ticketing.json`, `ui/site/app.js` |
| Token content | `groups` claim with full paths `/tenant/role` via group-membership mapper; `basic` scope for `sub` | realm JSON |
| Lifetimes | access token 300 s, SSO idle 1800 s; UI refreshes in background | realm JSON, `app.js` |
| Hardening | brute-force protection, registration off, `sslRequired: external`, redirect URIs/web origins pinned | realm JSON |
| Issuer | `KC_HOSTNAME=https://ticketing.localtest.me:8443/auth` (public URL) behind Kong with `KC_PROXY_HEADERS=xforwarded` | `k8s/30-keycloak.yaml` |
| Brokering | Google IdP (`trustEmail=true`), users start with no groups | `scripts/set-google.sh` |
| Admin automation | `kcadm.sh` inside the pod over localhost:8080 (not network-reachable) | `scripts/add-role.sh` |
| Consumers of keys | service: JWKS by `kid`; Kong: one static public key | `JwtDecoderConfig.java`, `render-kong.sh` |

## Local (narrowed) → Production (unwrapped)

> Local design unchanged; production items are **proposals requiring approval**.

| Q | Aspect | Local (narrowed) | Production (unwrapped) | Validate in production |
|---|---|---|---|---|
| Q1 | PKCE flow | same | same; plus refresh-token rotation | login journey synthetic check |
| Q2 | Audience | no `aud`; service checks `azp` | audience mapper `ticketing-api` + `aud` validation | token decode in staging shows `aud` |
| Q3 | Token storage/lifetimes | in-memory, 300 s | same; refresh rotation + reuse detection; consider DPoP | token lifetime config review |
| Q4 | Realm design | one realm, groups per tenant | same or Organizations; realm per compliance region | multi-tenant user test |
| Q5 | Google brokering | `trustEmail=true`, demo | confirm-link flow, `sub`-based identity, per-IdP trust policy | account-linking abuse test |
| Q6 | Hostname/issuer | `ticketing.localtest.me:8443` | real domain, issuer per environment/region | discovery `issuer` == service config |
| Q7 | Groups vs roles | groups `/tenant/role` | same; managed via admin API/IGA workflow | access review (quarterly recertification) |
| Q8 | Revocation window | ≤ 300 s | same or shorter; session revocation runbook; event-driven deny-list if required | revoke test measures time-to-deny |
| Q9 | Key rotation | default keys, never rotated | scheduled rotation with Kong coordination | rotation rehearsal |
| Q10 | HA | 1 replica, local caches | ≥ 2 replicas, Infinispan cluster, managed DB | kill-a-pod test keeps sessions |
| Q11 | Password grant | enabled for `e2e.sh` | **disabled**; dedicated test client/realm | client config audit |
| Q12 | Events | off | user + admin events to SIEM | alert test on `LOGIN_ERROR` burst |

## Prove it

```bash
B=https://ticketing.localtest.me:8443
curl -sk $B/auth/realms/ticketing/.well-known/openid-configuration | grep -oE '"(issuer|jwks_uri|token_endpoint)":"[^"]*"'
T=$(curl -sk $B/auth/realms/ticketing/protocol/openid-connect/token -d grant_type=password -d client_id=ticketing-ui -d username=alice -d 'password=Passw0rd!' -d scope=openid | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)
P=$(echo "$T" | cut -d. -f2 | tr '_-' '/+'); while [ $(( ${#P} % 4 )) -ne 0 ]; do P="$P="; done; echo "$P" | base64 -d; echo   # claims: iss, sub, typ=Bearer, azp, groups (no aud)
echo "$T" | cut -d. -f1 | base64 -d; echo                                                                                     # alg RS256 + kid
curl -sk $B/auth/realms/ticketing/protocol/openid-connect/certs | grep -oE '"kid":"[^"]*"|"alg":"[^"]*"|"use":"[^"]*"'
```

---

## Questions

### Q1. Why Authorization Code + PKCE for an SPA? Explain the attack PKCE prevents, step by step. ★★★★
**30-second headline:** A public client can't keep a secret; PKCE binds the code to a verifier only the starting tab knows, so an intercepted code is useless. S256, plus state against CSRF.
**Weak answer (what fails):** "PKCE replaces state" or "SPAs use implicit flow".
**Strong answer:** A public client cannot hold a secret, so a stolen authorization code (from a
malicious app registered on the same custom scheme, logs, browser history, or Referer) could be
redeemed by an attacker. PKCE: the client creates a random `code_verifier`, sends
`code_challenge = BASE64URL(SHA256(verifier))` with the authorisation request; at the token endpoint
it must present the verifier. The attacker has the code but not the verifier → redemption fails.
S256 rather than `plain` because `plain` leaks the verifier in the front channel.
**Applied here:** `login()` in `app.js` generates verifier/state; client attribute
`pkce.code.challenge.method: S256` enforces it even though the realm advertises `plain` too.
**Follow-ups / traps:** "Does PKCE replace `state`?" (no: `state` protects against login CSRF/mix-up;
both are used here.) "Why `Referrer-Policy: no-referrer`?" (the code sits in the callback URL.)

### Q2. The access token has no `aud` claim. Is that a problem? How does this system bind tokens to the API? ★★★★
**30-second headline:** Without aud any API trusting the realm accepts any token; this service compensates with azp + typ checks, and the clean fix is an audience mapper plus aud validation.
**Weak answer (what fails):** "No aud is fine because the signature is valid."
**Would I do it again?** I'd add the audience mapper from day one; azp checking works but couples the API to one client.
**Strong answer:** Without `aud`, any resource server trusting this issuer accepts any token from the
realm: token confusion across clients/APIs. The service compensates by requiring `azp=ticketing-ui`
(authorised party) and `typ=Bearer`. A cleaner design adds an **audience mapper** (e.g.
`aud=ticketing-api`) and validates `aud` at the service (and at the gateway with an OIDC plugin).
With multiple APIs, use per-API audiences or token exchange to down-scope.
**Prove it:** decode the token (above): no `aud`; `JwtValidatorTest` shows `azp=admin-cli` → rejected.

### Q3. Access token vs ID token vs refresh token: lifetimes, audience, storage and what happens if each leaks. ★★★★
**30-second headline:** Access token for APIs, short-lived; ID token for the client only; refresh token long-lived and the most dangerous to leak. Keep all in memory, rotate refresh tokens, consider DPoP.
**Weak answer (what fails):** Sending the ID token to APIs.
**Strong answer:** Access token: bearer for APIs, short (300 s), leak = API access until expiry.
ID token: for the client, contains identity claims, `aud`=client; must not be accepted by APIs (here
rejected via `typ`). Refresh token: long-lived (bound to SSO session), used only at the token endpoint
by the client; leak = ongoing access → keep in memory, enable refresh-token rotation/revocation on
reuse, bind with DPoP for public clients. This UI keeps all tokens in memory only.

### Q4. Realm-per-tenant vs groups-per-tenant in one realm. Which did we choose and when would you switch? ★★★★
**30-second headline:** One realm with /tenant/role groups because users belong to several tenants; realm-per-tenant buys per-tenant IdPs and branding at the cost of multiple identities and issuers.
**Weak answer (what fails):** "Realm per tenant is more secure" without the multi-tenant user problem.
**Would I do it again?** Yes; for B2B growth I'd evaluate Keycloak Organizations before splitting realms.
**Strong answer:** Chosen: **one realm, groups `/tenant/role`**: one login page, users can belong to
multiple tenants with different roles (a hard requirement), simple operations. Realm-per-tenant gives
per-tenant IdP configuration, branding, password policies, key isolation and admin delegation, but a
user in two tenants has two accounts, the issuer differs per tenant (gateway/service must trust N
issuers), and realms don't scale to thousands. Keycloak Organizations (26+) is a middle path for B2B
(org membership + per-org IdP). Hybrid: realm per *region/compliance zone*, groups for tenants.

### Q5. Google sign-in: what is the trust chain, and what are the account-takeover risks of `trustEmail=true`? ★★★★★
**30-second headline:** Keycloak trusts Google's verified email and links or creates an account; auto-linking on email is an account-takeover risk, so confirm links with re-authentication and key ownership on immutable sub.
**Weak answer (what fails):** "Google verifies users, so it's safe."
**Strong answer:** Browser → Keycloak → Google (OIDC); Keycloak validates Google's ID token, then
runs *first broker login*: creates or links a local user. `trustEmail=true` marks the email verified.
Risk: if *another* IdP (or a misconfigured one) asserts an email equal to an existing local user,
automatic linking could hand over the account. Mitigations: never auto-link without re-authentication
(Keycloak's default "confirm link + verify existing account" flow), only trust emails from IdPs that
verify them (Google does, `email_verified`), and key authorisation on immutable `sub` + IdP rather
than email. This app keys ownership on email: a known gap (`docs/08` §8.8).

### Q6. How does Keycloak know its public URL behind WAF → Kong, and what breaks if it is wrong? ★★★★
**30-second headline:** KC_HOSTNAME fixes the issuer and URLs behind the proxies; if wrong, issuer validation fails everywhere and redirects break. Admin console now has its own internal admin URL.
**Weak answer (what fails):** "Keycloak detects its URL."
**Since implemented:** KC_HOSTNAME_ADMIN now serves the admin console on an internal-only URL.
**Strong answer:** `KC_HOSTNAME` (full URL, hostname v2) fixes the frontend URL and therefore the token
`iss`, redirect URLs and discovery; `KC_PROXY_HEADERS=xforwarded` lets it trust `X-Forwarded-*` from
the proxy. If wrong: issuer mismatch at the service (`iss` validation fails → 401 everywhere),
redirect loops, cookies on the wrong domain. Kong's JWT secret is keyed by the issuer string, so it
breaks there too.
**Prove it:** discovery `issuer` equals `OIDC_ISSUER` in `k8s/40-ticket-service.yaml`.
**Follow-ups / traps:** "Trusting forwarded headers from anyone?" (only the proxy can reach Keycloak:
NetworkPolicy; otherwise header spoofing changes generated URLs.)

### Q7. Groups vs realm roles vs client roles for authorisation: why groups here? ★★★★
**30-second headline:** The role is tenant-scoped, and full-path groups give /tenant/role in one claim; client/realm roles are not tenant-scoped. Parse paths strictly.
**Weak answer (what fails):** "Roles are better than groups" without the tenant dimension.
**Strong answer:** The role is **tenant-scoped**, and groups give a natural `/tenant/role` path,
membership management in one place, and a single claim with full paths. Client roles are scoped to a
client, not a tenant; realm roles are global. Alternative: roles with tenant attributes or Keycloak
Authorization Services (policies/permissions), heavier. Danger: the app must parse paths strictly
(`TenantResolver` ignores malformed or unknown roles).
**Prove it:** `TenantResolverTest.parsesMembershipsAndIgnoresJunk`.

### Q8. A user is removed from `/acme/approver`. How long can they still approve tickets? Can you make it immediate? ★★★★★
**30-second headline:** Up to the token lifetime (≤ 300 s), because validation is local; immediate revocation costs latency or availability (introspection, deny-lists). State the window as a requirement.
**Weak answer (what fails):** "Immediately, Keycloak revokes it."
**Strong answer:** Until their current access token expires (≤ 300 s), and the UI refreshes it; the
refresh issues a token without the group, so ≤ 5 min. Immediate options: shorter token lifetime
(cost: more refreshes), revoke sessions (Keycloak admin → user → Sessions → sign out: refresh fails,
but issued access tokens remain valid until `exp` because validation is local), token introspection on
every request (latency + availability coupling), or event-driven cache of revoked `sid`/`sub` at the
gateway. Architects state the window explicitly in the security requirements.

### Q9. Key rotation in Keycloak: how does it work, and who in this system copes with it? ★★★★
**30-second headline:** Multiple keys with priorities; new key published, then activated, then old disabled. Spring follows kid via JWKS; Kong OSS needs a coordinated re-render.
**Weak answer (what fails):** "Rotation is transparent everywhere."
**Strong answer:** Keycloak supports multiple keys per algorithm with priorities and states
(active/passive/disabled); it signs with the highest-priority active key and publishes active+passive
in JWKS. Rotation: add new key, let JWKS consumers pick it up, make it active, later disable the old.
Spring (Nimbus) re-fetches JWKS on unknown `kid` → copes. Kong OSS has a static key → requires
`render-kong.sh` coordinated with activation (see Kong Q3).

### Q10. High availability of Keycloak: what state does it hold and how do you scale it? ★★★★
**30-second headline:** State is in the database plus Infinispan session caches; scale to ≥ 2 replicas with clustered caches and an HA database. Token validation does not depend on Keycloak being up.
**Weak answer (what fails):** "Keycloak is stateless."
**Strong answer:** Persistent state in the database; user/client sessions and caches in Infinispan
(embedded, replicated/distributed across pods). Scale with ≥ 2 replicas, `KC_CACHE=ispn` and a
discovery stack (Kubernetes/JDBC ping), sticky sessions not required in modern versions; DB is the
SPOF → managed HA Postgres. Without clustering, losing a pod logs out users whose sessions lived
there. Token validation does not depend on Keycloak availability (local JWT verification), only
login/refresh do: a key resilience property of this design.

### Q11. Why is the password grant enabled, and how would you test without it? ★★★
**30-second headline:** It exists only for the e2e script; it bypasses MFA and brute-force UX, so production disables it and tests with a browser flow or a dedicated test client.
**Weak answer (what fails):** Defending it as convenient.
**Strong answer:** For `scripts/e2e.sh` to obtain tokens via curl. It bypasses MFA/brute-force UX and
teaches bad patterns; disable in production. Test alternatives: drive the real browser flow
(Playwright), use a separate test realm/client, or a confidential test client with client credentials
plus a token-exchange to a test user (if policy allows).

### Q12. What Keycloak events would you enable and ship to the SIEM? ★★★
**30-second headline:** LOGIN, LOGIN_ERROR, CODE_TO_TOKEN_ERROR, IdP logins, password updates, and admin events (group membership = privilege change), shipped to the SIEM with alerts.
**Weak answer (what fails):** "Enable everything with full details" (stores PII).
**Since implemented:** implemented on 2026-10-05 for the ticketing and master realms, with SIEM alerts.
**Strong answer:** User events: `LOGIN`, `LOGIN_ERROR`, `CODE_TO_TOKEN_ERROR`, `REFRESH_TOKEN_ERROR`,
`IDENTITY_PROVIDER_LOGIN`, `UPDATE_PASSWORD`; admin events with representation (group membership
changes = privilege changes). Ship via event listener SPI or log-based (JBoss logging listener) to the
SIEM; alert on bursts of `LOGIN_ERROR` and on admin role changes. Disabled by default here
(`eventsEnabled:false`).
**Prove it:** `MSYS_NO_PATHCONV=1 kubectl -n auth exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh get realms/ticketing --config /tmp/kcadm.config --fields eventsEnabled,adminEventsEnabled` (after `kcadm config credentials`, see `docs/01`).

### Q13. Rapid fire
* Where are the realm's public keys? → `/auth/realms/ticketing/protocol/openid-connect/certs`.
* What makes the `groups` claim carry `/acme/applicant` rather than `applicant`? → mapper `full.path: true`.
* Which scope adds `sub` in Keycloak 26? → `basic`.
* Logout without a confirmation page? → pass `id_token_hint` to the end-session endpoint.
* Accepted gap about Keycloak here? → admin console reachable through the WAF.
