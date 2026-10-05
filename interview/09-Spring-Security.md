# Spring Security: resource server, filters, method security, tenancy

## Concepts you must own

* **Servlet filter chain:** `DelegatingFilterProxy` → `FilterChainProxy` → one matching
  `SecurityFilterChain` → ordered filters (… `BearerTokenAuthenticationFilter` … `AuthorizationFilter`).
* **Authentication vs authorisation:** `AuthenticationManager`/`AuthenticationProvider` produce an
  `Authentication` in the `SecurityContext`; `AuthorizationManager` decides access (HTTP rules and
  method security).
* **OAuth2 resource server (JWT):** `BearerTokenAuthenticationFilter` extracts the token →
  `JwtAuthenticationProvider` → `JwtDecoder` (Nimbus: JWKS fetch by `kid`, signature) →
  `OAuth2TokenValidator`s (timestamps with 60 s clock skew, issuer, custom) → `JwtAuthenticationConverter`
  → authorities.
* **Method security:** `@EnableMethodSecurity`, `@PreAuthorize` via AOP proxies; self-invocation and
  non-proxied calls bypass it.
* **Statelessness:** `SessionCreationPolicy.STATELESS`, no HTTP session; CSRF protection is for
  cookie-based (ambient) credentials.
* **401 vs 403:** `AuthenticationEntryPoint` (who are you?) vs `AccessDeniedHandler` (you may not).
* **SecurityContext / ThreadLocal:** per-thread; does not propagate to new threads unless using
  `DelegatingSecurityContextExecutor` or similar.

## How this application applies them

| Concept | Implementation | Where |
|---|---|---|
| One chain, deny by default | `/api/**` authenticated, everything else `denyAll()`; stateless; CSRF off (bearer only) | `SecurityConfig.java` |
| Transport identity first | `MtlsCallerFilter` **before** `BearerTokenAuthenticationFilter`: pins client cert CN to `kong-gateway` | `MtlsCallerFilter.java` |
| Hardened JWT validation | default validators + issuer + `typ=Bearer` + `azp=ticketing-ui` | `JwtDecoderConfig.validator()` |
| Tenant-scoped authorities | `TenantContextFilter` **after** bearer auth: resolves tenant from `groups` + `X-Tenant-ID`, replaces authorities with `ROLE_APPLICANT/APPROVER` *for that tenant*, binds `TenantContextHolder`, clears in `finally` | `TenantContextFilter.java` |
| Method security | class-level `@PreAuthorize("hasRole('APPROVER')")` / `('APPLICANT')`; service re-checks | controllers, `TicketService.require()` |
| Error contract | RFC 7807 bodies from filters (`Problems`) and controllers (`ApiExceptionHandler`); no stack traces | `Problems.java`, `application.yml` |
| Filters not double-registered | created with `new`, not `@Bean` | `SecurityConfig.java` comment |
| Tests | `@WebMvcTest` + `jwt()` post-processor; validator unit test | `ApiSecurityTest`, `JwtValidatorTest` |

## Local (narrowed) → Production (unwrapped)

> Local design unchanged; production items are **proposals requiring approval**.

| Q | Aspect | Local (narrowed) | Production (unwrapped) | Validate in production |
|---|---|---|---|---|
| Q1 | Filter order | same | same | slice tests + e2e in pipeline |
| Q2 | Tenant ThreadLocal | platform threads, cleared in `finally` | same; if async/virtual threads introduced, context propagation (ScopedValue/Micrometer) | concurrency test with mixed tenants |
| Q3 | CSRF | off (bearer only) | stays off unless a BFF/cookie session is introduced (then SameSite + CSRF tokens) | design review gate on auth changes |
| Q4 | Method security bypass | service re-checks; JPMS | + ArchUnit rule in CI | build fails on unannotated controller |
| Q5 | JWT validation | Nimbus + custom validators | same + `aud`; JWKS cache tuning | invalid-token canaries |
| Q6 | 404 vs 403 | same | same | BOLA tests in DAST |
| Q7 | Multiple issuers | single issuer | `JwtIssuerAuthenticationManagerResolver` with allow-list (regions/IdP migration) | per-issuer test tokens |
| Q8 | Error contract | RFC 7807, no stack traces | same + correlation ID in bodies | contract tests |
| Q9 | Security testing | unit/slice/Testcontainers/e2e | + DAST, mutation testing, contract tests | pipeline reports |
| Q10 | DPoP | not used | adopt if token theft is in the threat model | DPoP replay test |

## Prove it

```bash
mvn -q -pl ticket-api -am test -Dtest='ApiSecurityTest,JwtValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false
B=https://ticketing.localtest.me:8443
tok() { curl -sk $B/auth/realms/ticketing/protocol/openid-connect/token -d grant_type=password -d client_id=ticketing-ui -d "username=$1" -d 'password=Passw0rd!' -d scope=openid | grep -o "\"${2:-access_token}\":\"[^\"]*\"" | cut -d'"' -f4; }
curl -sk -o /dev/null -w 'ID token -> %{http_code}\n'              -H "Authorization: Bearer $(tok alice id_token)" $B/api/me   # 401
curl -sk -o /dev/null -w 'no tenant chosen (2 tenants) -> %{http_code}\n' -H "Authorization: Bearer $(tok alice)" $B/api/tickets # 400
curl -sk -o /dev/null -w 'unproven tenant -> %{http_code}\n'        -H "Authorization: Bearer $(tok alice)" -H 'X-Tenant-ID: initech' $B/api/tickets   # 403
curl -sk -o /dev/null -w 'approver raising ticket -> %{http_code}\n' -X POST -H "Authorization: Bearer $(tok bob)" -H 'Content-Type: application/json' -d '{"title":"t","mobile":"+919876543210","description":"d"}' $B/api/tickets  # 403
```

(Maven is not on the PATH on this laptop: use the full path from `docs/`/README, or `./mvnw` if added.)

---

## Questions

### Q1. Draw the filter order for an `/api` request and justify each custom position. ★★★★
**30-second headline:** mTLS caller check first (cheap rejection), then bearer authentication, then tenant resolution (needs the JWT, must precede authorisation), then authorisation and method security.
**Weak answer (what fails):** Not knowing where custom filters sit.
**Strong answer:** … `MtlsCallerFilter` → `BearerTokenAuthenticationFilter` → `TenantContextFilter`
→ … `AuthorizationFilter` → DispatcherServlet → method security → controller. mTLS caller pinning
first: reject untrusted workloads before spending CPU on JWT parsing (and before any token can be
logged/processed). Tenant filter after authentication because it needs the validated `Jwt`, and before
authorisation so `hasRole` sees tenant-scoped roles.
**Follow-ups / traps:** "Why not compute authorities in a `JwtAuthenticationConverter`?" (the converter
has no access to the HTTP request, and the active tenant comes from the `X-Tenant-ID` header; you could
use `RequestContextHolder` or a request-aware `AuthenticationManagerResolver`, but the filter keeps
tenant resolution explicit and testable.)

### Q2. The tenant context lives in a ThreadLocal. Name three ways it can leak or vanish, and how this code defends. ★★★★★
**30-second headline:** Thread reuse (cleared in finally), async work (fails closed), virtual threads/reactive (needs explicit propagation); prefer ScopedValue or context-propagation as the code grows.
**Weak answer (what fails):** "ThreadLocal is fine, Spring handles it."
**Would I do it again?** Yes for now; with async features I'd switch to explicit context propagation.
**Strong answer:** (1) Thread reuse in Tomcat pools: a missing `clear()` leaks tenant A into tenant
B's next request → `finally { TenantContextHolder.clear(); }`. (2) Async work (`@Async`,
`CompletableFuture`, parallel streams) runs on other threads → context missing; it **fails closed**
because `require()` throws and Hibernate uses a no-row tenant (`__no_tenant__`). (3) Virtual threads /
reactive: ThreadLocal per virtual thread is fine but must be propagated to child tasks; Reactor needs
Context, not ThreadLocal. Better long-term: Java `ScopedValue` or Micrometer context-propagation.
**Prove it:** `TenantResolverTest.holderFailsClosedWhenUnset`.

### Q3. CSRF is disabled. Defend it, and tell me exactly what change would make it a vulnerability. ★★★★
**30-second headline:** CSRF needs ambient credentials; this API uses only bearer headers set by JavaScript. It becomes vulnerable the day tokens move into cookies without SameSite and CSRF tokens.
**Weak answer (what fails):** "CSRF doesn't apply to REST APIs."
**Strong answer:** CSRF exploits *ambient* credentials the browser attaches automatically (cookies,
Basic auth). This API authenticates only with an `Authorization: Bearer` header set by JavaScript;
a cross-site form cannot add it. It becomes vulnerable the day someone adds cookie-based sessions or
puts the token in a cookie ("BFF" pattern) without SameSite + CSRF tokens. Also CORS: no CORS
allowance exists (same origin via the gateway), so cross-origin JS cannot read responses.

### Q4. `@PreAuthorize` is on the class. Give me two ways a future developer can bypass it without noticing. ★★★★
**30-second headline:** Self-invocation and new entry points without annotations; mitigated by service-level re-checks, JPMS and RLS, and enforceable with an ArchUnit rule.
**Weak answer (what fails):** "Class-level annotations cover everything."
**Strong answer:** (1) Calling a protected method from inside the same bean (self-invocation skips
the proxy). (2) Adding a new controller without the annotation, or a `@Scheduled`/message listener
that calls repositories directly. Defences here: `TicketService.require(role)` re-checks inside the
service; JPMS prevents the API module from touching repositories at all; RLS stops cross-tenant data at
the DB. Add an ArchUnit rule: every `@RestController` must carry `@PreAuthorize`.

### Q5. Walk through JWT validation in detail: what does Nimbus check, in what order, and with what tolerances? ★★★★
**30-second headline:** Key by kid from JWKS, signature, then validators: timestamps with 60 s skew, issuer, typ, azp; any failure is 401 with WWW-Authenticate.
**Weak answer (what fails):** Not knowing about clock skew or kid lookup.
**Strong answer:** Parse → header `alg` must match the JWKS key type (RS256) → select key by `kid`
(fetch/refresh JWKS if unknown, cached) → verify signature → claims set → validators:
`JwtTimestampValidator` (`exp`, `nbf` with 60 s skew), `JwtIssuerValidator`, custom `typ` and `azp`
claim validators (`DelegatingOAuth2TokenValidator`, all must pass). Failure → `InvalidBearerTokenException`
→ 401 with `WWW-Authenticate: Bearer error="invalid_token"`. `alg=none` and HMAC-with-public-key
confusion are rejected because the decoder is configured with a JWKS (asymmetric) source.
**Prove it:** `JwtValidatorTest` (4 cases).

### Q6. Why does another user's ticket return 404 but a wrong role returns 403? Isn't that inconsistent? ★★★
**30-second headline:** Role failures reveal nothing the caller doesn't know (403); ownership failures would reveal existence, so they are 404 (anti-BOLA).
**Weak answer (what fails):** "It should be 403 everywhere for consistency."
**Strong answer:** Different questions. Role → *capability* known to the caller (they know they are an
applicant) → 403 is honest and helps the UI. Ownership → *existence* of a resource the caller must not
learn about → 404 prevents enumeration (OWASP API1 BOLA). Same for cross-tenant ids (RLS returns
nothing → 404).

### Q7. How would you support two issuers (e.g. a second Keycloak region or an enterprise IdP)? ★★★★
**30-second headline:** An allow-listed issuer resolver with a decoder and validators per issuer, plus claim normalisation; never trust the issuer named inside the token.
**Weak answer (what fails):** Accepting any issuer found in the token.
**Strong answer:** `JwtIssuerAuthenticationManagerResolver` with an allow-list of trusted issuers
(never "trust any issuer in the token", which lets an attacker host their own IdP). Each issuer gets
its own decoder + validators; tenant/role mapping may differ per issuer (claims normalisation layer).
Kong needs a JWT secret per issuer key.

### Q8. Errors thrown inside filters don't reach `@RestControllerAdvice`. How does this code keep a consistent error contract? ★★★
**30-second headline:** Filters write RFC 7807 bodies themselves; controllers use the advice; framework messages are replaced and stack traces never leave the service.
**Weak answer (what fails):** "The ControllerAdvice handles all errors."
**Strong answer:** Filter errors are written directly with `Problems.write` (RFC 7807 JSON, correct
status: 400 tenant selection, 403 tenant access / workload identity); controller errors go through
`ApiExceptionHandler`; framework messages (AccessDenied) are replaced by a neutral message;
`server.error.include-message: never` stops leaking exception text.

### Q9. Security testing strategy: what does each test layer prove here and what is missing? ★★★★
**30-second headline:** Unit, slice (mocked decoder), separate validator tests, Testcontainers integration, live e2e; missing DAST, mutation testing and contract tests.
**Weak answer (what fails):** "We have 80 % coverage."
**Since implemented:** CI now fails on skipped tests.
**Strong answer:** Unit: resolver parsing and fail-closed holder; workflow rules. Slice
(`@WebMvcTest` + `jwt()`): role/tenant mapping, body cannot set tenant/email, header must be proven;
note it **bypasses** the decoder (mocked), so `JwtValidatorTest` tests validators separately.
Integration (Testcontainers): RLS, separation of duties at DB, paging. E2E: real tokens, mTLS,
NetworkPolicies. Missing: fuzzing/DAST (OWASP ZAP), mutation testing on security rules, contract test
that every endpoint is protected.

### Q10. Spring Security 6.5 supports DPoP. Would you adopt it here? ★★★★
**30-second headline:** DPoP makes stolen tokens unusable; worth it when token theft is in the threat model, at the cost of client key handling and proxy-aware proof validation.
**Weak answer (what fails):** Adopting it without considering the proxies' effect on htu/htm.
**Strong answer:** DPoP sender-constrains tokens: the client proves possession of a private key per
request, so a stolen bearer token is useless. Valuable for a public SPA whose tokens could be
exfiltrated by XSS (mitigated by CSP) or logged by an intermediary (WAF/Kong terminate TLS). Costs:
Keycloak DPoP support and client changes (WebCrypto keys), gateway must forward the `DPoP` header and
not break proof binding (`htu`/`htm` must match the public URL behind proxies). Adopt when token theft
is in the threat model; until then short lifetimes + CSP.

### Q11. Rapid fire
* Where is `ROLE_` added? → `TenantContextFilter` maps `Role` → `ROLE_<NAME>`; `hasRole('APPROVER')` adds the prefix when checking.
* Why is `/api/me` excluded from the tenant filter? → it lists tenants, so it must work before one is chosen.
* Status when the tenant header is missing but the user has 2 tenants? → 400.
* What stops the body from setting `tenantId`/`email`? → DTOs don't have those fields; values come from the context.
* Default clock skew? → 60 seconds.
