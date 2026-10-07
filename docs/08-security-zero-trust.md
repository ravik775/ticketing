# 8. Security and Zero Trust

## 8.1 What "Zero Trust" means here

Traditional security trusts everything *inside* the network ("the castle wall"). **Zero Trust** assumes
an attacker may already be inside and therefore:

1. **Never trust network location.** Being "inside the cluster" grants nothing.
2. **Verify explicitly, at every hop.** Each component checks *who* is calling (identity), *what* they
   may do (authorisation) and protects the connection (encryption).
3. **Least privilege.** Every user, program and connection gets only what it needs.
4. **Assume breach.** Design so that one compromised part cannot reach everything; record evidence.

The sections below show how each principle is applied at the **service boundaries** (between
components) and **within the pods** (inside each component), and how you can check it.

## 8.2 Defence in depth: the layers a request passes

```
Browser
  │ [1] TLS 1.2/1.3 · strict browser headers (CSP, no framing) · token only in memory
  ▼
WAF (edge)          [2] OWASP CRS attack detection · allowed paths/methods/content types · host check
  │                     size & time limits · TLS to Kong with certificate verification
  ▼   NetworkPolicy: only the WAF may reach Kong
Kong (gateway)      [3] JWT signature + expiry · per-user rate limit · 1 MB body limit · correlation ID
  │                     mTLS client certificate toward the API
  ▼   NetworkPolicy: only Kong may reach the API
ticket-service      [4] mTLS required + caller name pinned · JWT verified again (issuer, type, client)
  │                     tenant derived from the token only · role per tenant · separation of duties
  │                     ownership checks · input validation · Java module boundaries
  ▼   NetworkPolicy: only the API (and Keycloak) may reach the databases
PostgreSQL          [5] Row-Level Security, forced, fail-closed · constraints enforce the business rules
MongoDB             [6] authenticated user limited to one database · tenant filter on every query
```

An attacker has to defeat every layer; most layers are independent of each other.

## 8.3 Zero Trust at each service boundary

### Browser → WAF (the internet edge)

| Control | Detail | Where |
|---|---|---|
| Encryption | TLS 1.2 and 1.3 only, modern ciphers; TLS 1.1 refused | `k8s/80-waf.yaml` |
| Single entry point | only the WAF has a public port; Kong, Keycloak, the API and databases have none | Services in `k8s/*.yaml` |
| Attack filtering | OWASP Core Rule Set (SQL injection, XSS, path traversal, scanners, protocol abuse…) | guide 9 |
| Positive security model | only the application's paths, the methods it uses, JSON bodies for the API | custom rules in `k8s/80-waf.yaml` |
| Host check | requests for any other host name are refused (421) | `k8s/80-waf.yaml` |
| Resource limits | 1 MB body, 10 s header/body timeouts, 16 KB headers | `k8s/80-waf.yaml` |
| Privacy of logs | the WAF audit log never records headers or bodies (passwords, tokens) | `MODSEC_AUDIT_LOG_PARTS=AHZ` |

### WAF → Kong

| Control | Detail |
|---|---|
| Encryption + server identity | new TLS connection; the WAF verifies Kong's certificate against the cluster CA and the name `ticketing.localtest.me` (`proxy_ssl_verify on`) |
| Network restriction | NetworkPolicy `kong-ingress-from-waf`: Kong accepts connections **only** from the WAF pod |

### Kong (the gateway)

| Control | Detail | Where |
|---|---|---|
| Authentication | `/api` requires a valid **JWT**: RS256 signature with Keycloak's key, not expired | `scripts/render-kong.sh` (`jwt` plugin) |
| Per-user rate limit | 300 requests/minute per user (`sub` claim), counted only for verified tokens | `pre-function` + `rate-limiting` |
| Request size | 1 MB | `request-size-limiting` |
| Traceability | `X-Correlation-ID` on every API call | `correlation-id` |
| Security headers | `Strict-Transport-Security`, `X-Content-Type-Options: nosniff` | `response-transformer` |
| No admin API | Kong's admin interface is switched off (`KONG_ADMIN_LISTEN=off`); configuration is a file | `k8s/60-kong.yaml` |

### Kong → ticket-service (mutual TLS)

| Control | Detail |
|---|---|
| Both sides prove identity | Kong verifies the service certificate; the service **requires** a client certificate signed by the cluster CA (`client-auth: need`) |
| Caller pinning | the service accepts only the certificate name `kong-gateway` (`MtlsCallerFilter`); other certificates from the same CA get 403 |
| Network restriction | NetworkPolicy: only the Kong pod may connect to port 8443 |
| Still needs a user | a valid gateway certificate **alone** gets 401: the user's JWT is always required |

See [guide 5](05-mtls-and-certificate-rotation.md) for the handshake step by step.

### Inside ticket-service: every request is re-verified

| Control | Detail | Code |
|---|---|---|
| JWT verified again | signature (keys fetched from Keycloak over TLS), expiry, **issuer**, `typ=Bearer` (an ID token is refused), `azp` must be `ticketing-ui` or `ticketing-mcp` (tokens of other clients refused); `ticketing-mcp` tokens must also carry `aud=ticketing-api` | `JwtDecoderConfig.java` |
| Tenant from the token only | memberships come from the token's `groups`; the `X-Tenant-ID` header only *selects* among tenants the token proves; email and tenant are never read from the request body | `TenantResolver.java`, `TenantContextFilter.java` |
| Roles per tenant | `ROLE_APPLICANT` / `ROLE_APPROVER` are computed for the **active tenant only**; `@PreAuthorize` on every controller | `TicketController`, `ApprovalController` |
| Defence in depth | `TicketService` re-checks the role itself, so a future controller cannot forget | `TicketService.java` |
| Separation of duties | an approver can never pick up, unlock or decide a ticket they raised (403), enforced in the domain model **and** by a database constraint | `TicketWorkflow.java`, `V3__separation_of_duties.sql` |
| No information leaks | another user's ticket returns 404 (not 403), so existence is not revealed; error responses never contain stack traces | `TicketService.ownedBy`, `application.yml` |
| Input validation | title ≤ 120, description ≤ 4000, mobile `+?[0-9]{7,15}`, comments ≤ 2000, page size ≤ 200 | request records, controllers |
| Concurrency safety | optimistic locking (`version`) stops two approvers both winning a race | `TicketWorkflow.java` |
| Code boundaries | Java modules: the web layer cannot compile against database classes, so it cannot bypass `TicketService` | `module-info.java` files |
| Request isolation | the tenant context is a per-request value that is always cleared at the end of the request | `TenantContextFilter.java` |
| AI agents (MCP) | `/api/mcp` sits behind the same WAF rules, Kong route and filter chain as REST; tools call the same `TicketService` methods (roles, separation of duties) and the same validation; every history event records the channel (`REST`/`MCP`) next to the actor ([guide 10](10-mcp-integration.md)) | `TicketMcpTools.java` |

### ticket-service → databases

| Control | Detail |
|---|---|
| Row-Level Security (PostgreSQL) | `ticket_workflow` is `FORCE`d RLS: each transaction first binds `app.tenant_id`; without it **no** rows are visible (fail-closed), and rows of other tenants can neither be read nor written |
| Second filter | Hibernate `@TenantId` adds `tenant_id = :current` to every query as well |
| Constraints | the database itself refuses an impossible lock state or an owner holding the lock |
| MongoDB isolation | only one class touches the collection, and it adds `tenantId` from the security context to every query and refuses to insert a document of another tenant |
| Least privilege | MongoDB user `ticketing_app` has `readWrite` on the `ticketing` database only; separate PostgreSQL users for the application and for Keycloak |
| Network restriction | PostgreSQL accepts only ticket-service and Keycloak; MongoDB only ticket-service |

### Keycloak (identity)

| Control | Detail |
|---|---|
| Modern login flow | Authorization Code + **PKCE (S256)**, public client, no implicit flow |
| Short-lived tokens | access token 5 minutes, SSO idle timeout 30 minutes |
| Password guessing | brute-force detection on; self-registration off |
| Federation | Google sign-in through Keycloak; Google users get **no** access until an administrator assigns a role |
| Network | reachable only from Kong and ticket-service; its internal HTTP port 8080 is not reachable from any pod; outbound only to PostgreSQL and HTTPS for Google |

### In the browser

| Control | Detail |
|---|---|
| Content-Security-Policy | only scripts/styles from the site itself; no inline code; no framing (`frame-ancestors 'none'`) |
| Other headers | `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` (the login code in the URL never leaks), HSTS |
| Token handling | tokens live only in page memory (not cookies/localStorage); renewed with the refresh token |
| Safe rendering | ticket text is always inserted as text, never as HTML, so stored content cannot inject scripts |

## 8.4 Zero Trust within the pods

Every pod is hardened so that, even if an attacker found a flaw in a program, they could do very little
inside the container.

| Setting | Meaning | ticket-service | Kong | Keycloak | WAF | UI | PostgreSQL | MongoDB |
|---|---|---|---|---|---|---|---|---|
| `runAsNonRoot` | never runs as the all-powerful *root* user | ✔ (uid 10001) | ✔ (1000) | ✔ | ✔ (101) | ✔ | ✔ (999) | ✔ (999) |
| `allowPrivilegeEscalation: false` | a process cannot gain more rights than it started with | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| `capabilities: drop [ALL]` | removes all special Linux kernel rights | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| `seccompProfile: RuntimeDefault` | blocks dangerous system calls | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| `automountServiceAccountToken: false` | no Kubernetes API credentials inside the pod (a compromised pod cannot talk to the cluster API) | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| `readOnlyRootFilesystem` | the program files cannot be modified | ✔ | – | – | – | – | – | – |
| CPU/memory requests and limits | one component cannot starve the others | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| Secrets mounted read-only | certificates/keys cannot be altered by the pod | ✔ | ✔ | ✔ | ✔ | n/a | n/a | n/a |

Inside the Java service, the module system (8.3) and the per-request tenant context provide the same
"least privilege" idea at code level: each part of the program can reach only what it needs.

## 8.5 The network firewall between pods (NetworkPolicies)

Every namespace starts with **default deny for incoming and outgoing traffic**; then only these
connections are allowed (`k8s/70-network-policies.yaml`):

| From → To | Port | Why |
|---|---|---|
| anywhere → WAF | 8443 | public entry |
| WAF → Kong | 8443 | forward clean requests |
| Kong → UI | 8080 | static web app |
| Kong → Keycloak | 8443 | login pages, token endpoint |
| Kong → ticket-service | 8443 | API (mTLS) |
| ticket-service → Keycloak | 8443 | token-signing keys |
| ticket-service → PostgreSQL / MongoDB | 5432 / 27017 | data |
| Keycloak → PostgreSQL | 5432 | Keycloak's data |
| Keycloak → internet (public addresses only) | 443 | Google sign-in |
| every pod → cluster DNS | 53 | name resolution |

`scripts/e2e.sh` proves it by starting a temporary "rogue" pod and showing it cannot reach the API,
Keycloak or Kong.

## 8.6 Secrets

| Secret | Holds | Created by |
|---|---|---|
| `*-tls` secrets | certificates and private keys | cert-manager (automatic) |
| `postgres-credentials`, `mongo-credentials`, `mongo-keyfile`, `ticket-service-env`, `keycloak-env`, `grafana-admin` | database, replica-set and admin credentials (random, 32 characters) | **OpenBao** (namespace `secrets`), copied in by External Secrets; each namespace can read only its own path (`scripts/secrets-bootstrap.sh`, `--rotate` to change them) |
| `kong-declarative-config` | Kong configuration incl. its client key | `scripts/render-kong.sh` |
| Keycloak (inside its database) | Google client secret | `scripts/set-google.sh` |

Secrets are given only to the pods that need them (as environment variables or read-only files).
No password is stored in Git. The OpenBao root token is kept outside the repository in
`~/.ticketing/openbao-init.json`; OpenBao's own audit device logs every read to Loki. See 7.7 for the
AWS Secrets Manager equivalent.

## 8.7 How you can verify the controls

`bash scripts/e2e.sh` runs 53 checks against the live system, including:

| Area | Examples of what is proven |
|---|---|
| Authentication | no token → 401; an ID token instead of an access token → 401 |
| Tenant isolation | tenant from the token, not the body; another tenant's ticket → 404; header for an unproven tenant → 403 |
| Roles | approver cannot raise tickets; applicant cannot use approver endpoints |
| Locking | a second approver cannot claim or decide a locked ticket |
| Rate limiting | one quota per user across tokens; a forged subject header is ignored |
| WAF | SQL injection, XSS, scanners, unknown paths, wrong methods/content types, unknown hosts are blocked |
| mTLS | no client certificate → refused; gateway certificate without JWT → 401 |
| Network | a rogue pod cannot reach the API, Keycloak or Kong |

The Java test suite (`mvn verify`) adds unit and integration tests for the same rules, including a
test that PostgreSQL itself refuses cross-tenant rows and owner-held locks.

## 8.8 Known gaps and accepted risks (laptop installation)

Honest list of what is **not** production-grade yet, with the fix. The two former
**accepted gaps** were closed on 2026-10-05. The full enterprise scorecard is in
[enterprise-gap.md](enterprise-gap.md).

| Gap | Risk | Fix | Status |
|---|---|---|---|
| Demo passwords in `k8s/kustomization.yaml` (and in the GitHub repository) | anyone with the repository knows them | real secrets store (7.7), new passwords | **Closed**: credentials live in OpenBao, delivered by External Secrets, rotated to random values; demo *end-user* passwords remain in the realm import for testing |
| Password grant enabled on `ticketing-ui` (for `e2e.sh`) | allows password login outside the browser flow | disable in production (7.7) | open |
| Keycloak admin console and `master` realm reachable through the WAF | admin login exposed to the network | block `/auth/admin` and `/auth/realms/master` publicly; admin via VPN/port-forward | **Closed**: WAF rule 1000100 and a Kong route return 403; admins use `kubectl port-forward` (guide 1); failed master-realm logins raise an alert |
| Database connections not encrypted inside the cluster | traffic visible to someone with node access | TLS to RDS/DocumentDB on AWS (7.6) or a service mesh | open |
| Kong → UI over plain HTTP | static files only, inside the cluster | TLS on the UI pod or serve from S3/CloudFront | open |
| Any namespace may request a certificate from the cluster-wide CA (including the name `kong-gateway`) | a rogue workload with that ability could impersonate Kong at the TLS level | NetworkPolicy already blocks it; also use a namespaced Issuer or cert-manager approver-policy | open |
| Renewed certificates need `scripts/reload-certs.sh` | service outage when the old certificate expires if forgotten | monthly routine (guide 5); automatic reload (5.8) | open, mitigated: `CertificateExpiresSoon` / `CertificateNotReady` alerts |
| Users identified by email address | a changed/reused email changes who owns tickets | key identities on the token's `sub` | open |
| Java module boundaries checked at build time only | not enforced by the JVM at run time | acceptable; code review + build checks | open |
| Rate-limit counters per Kong copy; real client IP hidden by the laptop load balancer | per-IP limits impossible on the laptop | Redis policy and ALB client IPs on AWS (7.3, 7.5) | open |
| Images (except the WAF) referenced by tag, not digest | a changed upstream image could be pulled | pin digests / private registry (7.7) | open |
| Keycloak login events off by default | less evidence after an incident | enable (guide 2, 2.6) | open |
| No backups, single replicas | data loss / downtime | AWS managed databases and replicas (7.8) | open |
