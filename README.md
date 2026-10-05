# Multi-Tenant Ticketing on Kubernetes (Kong + Keycloak + Spring Boot)

A multi-tenant ticketing application that demonstrates, end to end, how an enterprise stack fits
together on a single-node Kubernetes cluster (k3d/k3s) on a laptop.

* **Applicants** raise tickets (title, mobile number, description) and see only their own.
  Email and tenant come from the security context, never from the request body.
* **Approvers** work at tenant level: they see every ticket of the tenant and can only
  *Approve*, *Reject* or *Request more details*, always with a comment. They cannot raise tickets.
* A ticket picked up by an approver is **locked**; no other approver can act on it until it is
  **unlocked** (any approver of the tenant may unlock), after which they can pick it up and approve it.
* One user may belong to **several tenants**, with a different role in each.
* **Separation of duties:** a user who is both applicant and approver in a tenant can never pick up, unlock or
  decide a ticket they raised themselves (403). Enforced in the domain model and by a database constraint.

> **Operations handbook:** step-by-step guides for maintainers (users and roles, logs, databases,
> architecture, certificates, OpenSSL, AWS, security, WAF) are in **[docs/](docs/README.md)**.

## Architecture

```
Browser ──HTTPS──► WAF (only entry point: ModSecurity + OWASP CRS, TLS, path/method allow-list)
                     │ TLS, Kong's certificate verified
                     ▼
                   Kong OSS (internal only: JWT check, per-user rate limit)
                     ├─ /        → ui (nginx, static SPA)
                     ├─ /auth    → Keycloak (OIDC, Google sign-in)
                     └─ /api     → ticket-service  ──mTLS──►   (re-validates JWT, tenant, role)
                                        ├─► PostgreSQL  structured data: tenant registry, ticket workflow
                                        │               state (status/lock/owner/version) with Row-Level Security
                                        └─► MongoDB     ticket details (title, mobile, description) + history/comments
```

| Namespace | Contents |
|-----------|----------|
| `edge` | Web Application Firewall (nginx + ModSecurity v3 + OWASP CRS 4), the only public service |
| `gateway` | Kong OSS (DB-less, declarative config) |
| `auth` | Keycloak |
| `ticketing` | ticket-service, ui, PostgreSQL, MongoDB |

### Why two databases
* **PostgreSQL** holds the *structured, transactional* data: the tenant registry, each ticket's workflow row
  (status, lock holder, owner, optimistic-lock version) and Keycloak's own database. The lock/approval rules
  need atomic, constrained updates, which fits a relational store (and its Row-Level Security).
* **MongoDB** holds the *flexible ticket content*: details and the embedded history/comments.
* They are separate stores, so there is no cross-database transaction. `TicketService` writes the Mongo
  document first and deletes it if the Postgres insert fails; later history events are appended after the
  Postgres change commits (a failure there is logged). For this demo that is a deliberate, documented trade-off.

## Security model

| Requirement | How it is met |
|---|---|
| Strict tenant isolation | Four layers: (1) tenant taken only from the validated JWT (`groups` claim) and the `X-Tenant-ID` selector is honoured only if the token proves membership; (2) Hibernate `@TenantId` filters every Postgres query; (3) Postgres **Row-Level Security** (`FORCE`d, fail-closed when no tenant is bound); (4) every MongoDB query is constrained by `tenantId`, in the only class allowed to touch the collection |
| Java *Module* security (JPMS) | Three named modules with `module-info.java`: `com.ticketing.security` (pure Java), `com.ticketing.core` (exports **only** `TicketService` and its DTOs; the entity, repositories and Mongo store are in a non-exported package) and `com.ticketing.api`. The REST layer **cannot compile** against the persistence classes, so it cannot bypass tenant/role checks. Method security (`@PreAuthorize`) adds role checks per tenant |
| Zero Trust | No implicit trust from network position. Browser→WAF: TLS, attack filtering (OWASP CRS) and an allow-list of paths/methods. WAF→Kong: TLS with certificate verification; only the WAF may reach Kong. Kong: JWT verified. Kong→service: **mTLS** (cert-manager CA, client cert `CN=kong-gateway`) *and* the service re-verifies the JWT, resolves tenant/role and pins the caller identity (only `kong-gateway`). Service→Keycloak: TLS with the cluster CA |
| Pod ingress/egress rules | Default-deny ingress **and** egress in all four namespaces plus explicit allows only (see `k8s/70-network-policies.yaml`). Pods: non-root, dropped capabilities, no service-account token, read-only root FS for the service |
| OAuth 2.0 / OIDC / JWT | Keycloak realm `ticketing`, SPA client with Authorization Code + **PKCE**; RS256 access tokens. The service accepts only **access** tokens (`typ=Bearer`) issued to the UI client (`azp=ticketing-ui`): ID tokens and tokens of other realm clients are refused |
| RBAC | Roles per tenant from Keycloak groups: `/<tenant>/applicant`, `/<tenant>/approver`; approvers cannot act on their own tickets (separation of duties, also a DB `CHECK`) |
| Rate limiting | Kong limits `/api` **per user** (the JWT `sub` claim), 300 requests/minute. Every Keycloak token maps to one Kong consumer, so the default per-consumer limit would be a single quota for everybody; a `pre-function` copies the `sub` claim into a gateway-owned header that `rate-limiting` keys on. It runs before `jwt`, but `rate-limiting` runs after it, so only verified tokens are counted, and a client-sent copy of that header is always replaced |
| Browser hardening | The UI is served with a strict Content-Security-Policy (no inline script/style), `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` (`ui/default.conf`) |
| Kong OSS | No OIDC plugin in OSS, so the OSS `jwt` plugin verifies signature and expiry with the realm's public key (rendered by `scripts/render-kong.sh`) |

## Prerequisites (Windows: run the scripts from Git Bash or WSL)

Docker Desktop, [k3d](https://k3d.io), `kubectl`, JDK 21, Maven 3.9+. Internet access is needed to pull images and
Maven dependencies. The hostname `ticketing.localtest.me` resolves to 127.0.0.1 via public DNS.

## Run

```bash
mvn verify            # compiles the three modules and runs the tests
bash scripts/up.sh    # cluster + images + cert-manager + everything; takes a few minutes
bash scripts/e2e.sh   # 53 end-to-end checks through the WAF and Kong, incl. WAF, mTLS and NetworkPolicy checks
bash scripts/down.sh  # delete the cluster
```

Open **https://ticketing.localtest.me:8443** (self-signed certificate: accept the browser warning).

| User | Password | Role |
|---|---|---|
| alice | `Passw0rd!` | applicant in **acme** *and* **globex** |
| erin | `Passw0rd!` | applicant in acme |
| bob, carol | `Passw0rd!` | approvers in acme |
| dave | `Passw0rd!` | approver in globex |

Try: sign in as alice, raise a ticket in acme → sign in as bob (private window), pick it up → carol cannot
pick it up or decide → carol unlocks it, picks it up and approves → alice sees the outcome and comments.

### REST API (all under `/api`, `Authorization: Bearer <jwt>`, `X-Tenant-ID: <tenant>` if you belong to several)

| Method & path | Who | Purpose |
|---|---|---|
| `GET /me` | any user | email and tenants/roles proven by the token |
| `POST /tickets` | applicant | raise a ticket `{title, mobile, description}` |
| `GET /tickets?page=&size=`, `GET /tickets/{id}` | applicant | own tickets only (others → 404); paged, `size` 1–200 (default 50) |
| `POST /tickets/{id}/respond` | applicant | answer a "more details" request |
| `GET /approvals/tickets?status=&page=&size=`, `GET /approvals/tickets/{id}` | approver | all tickets of the tenant; paged like above |
| `POST /approvals/tickets/{id}/claim` | approver | pick up / lock (409 if locked, 403 on your own ticket) |
| `POST /approvals/tickets/{id}/unlock` | approver | release a lock, including another approver's (403 on your own ticket) |
| `POST /approvals/tickets/{id}/decision` | approver | `{decision: APPROVE\|REJECT\|REQUEST_INFO, comment}`; only the lock holder |

Locks do not expire by default. Set `TICKET_LOCK_TIMEOUT` (ISO-8601, e.g. `PT30M`) on the ticket-service to let
another approver take over a lock older than that; the takeover is recorded in the ticket history.

## Google sign-in

Create a *Web* OAuth client in Google Cloud with redirect URI
`https://ticketing.localtest.me:8443/auth/realms/ticketing/broker/google/endpoint`, then:

```bash
scripts/set-google.sh <client-id> <client-secret>
```

Keycloak's NetworkPolicy allows outbound HTTPS to public addresses for this (the code exchange with Google is
server-side); private and cluster ranges stay blocked.

Google users start with **no tenant** and get a clear message in the UI until an administrator adds them to a
group (e.g. `/acme/applicant`) in the Keycloak admin console (`https://ticketing.localtest.me:8443/auth/admin`,
user `admin`, demo password in `k8s/kustomization.yaml`).

## Repository layout

```
pom.xml                    parent (Spring Boot 3.5, Java 21)
ticket-security/           tenant context + role/tenant resolution (pure Java module)
ticket-core/               TicketService facade (exported); entity, Postgres + Mongo stores (not exported); Flyway SQL
ticket-api/                REST controllers, Spring Security (JWT, mTLS identity, tenant filter), Dockerfile
ui/                        static SPA (PKCE login, applicant and approver views) + Dockerfile
docs/                      operations handbook for maintainers (start at docs/README.md)
k8s/                       kustomize: namespaces, cert-manager PKI, Postgres, MongoDB, Keycloak (+realm), service, ui, Kong, NetworkPolicies, WAF
scripts/                   up.sh, down.sh, render-kong.sh, e2e.sh, set-google.sh, add-role.sh, reload-certs.sh
```

## Known limits (deliberate, to keep the demo small)

* **Demo credentials** are in `k8s/kustomization.yaml`; the Keycloak client allows the password grant only so
  `e2e.sh` can fetch tokens with curl. Remove both for anything real.
* PostgreSQL and MongoDB traffic is plain inside the cluster and protected by NetworkPolicies only (add TLS or a
  service mesh next). The UI is served over plain HTTP between Kong and the nginx pod.
* JPMS is enforced at **compile time**: Spring Boot runs the fat jar on the classpath, so the boundary is not
  re-enforced by the JVM at runtime.
* Kong holds a single JWT verification key read from Keycloak at deploy time; re-run `scripts/render-kong.sh`
  if the realm's signing key changes. The cert-manager certificates are valid 90 days (CA 10 years).
* Single replicas, no backups. Kubelet TCP probes are used because mTLS prevents HTTP probes.
* Rate-limit counters are local to each Kong pod (`policy: local`); with several Kong replicas use the
  `redis` policy so a user's quota is shared.
* The UI renews its 5-minute access token with the refresh token (kept in memory only). If the SSO session
  has ended, unsent form text is kept in `sessionStorage` across the sign-in redirect and restored.
