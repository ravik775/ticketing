# Multi-Tenant Ticketing Platform: Zero Trust SaaS reference on Kubernetes

[![CI](https://github.com/ravik775/ticketing/actions/workflows/ci.yml/badge.svg)](https://github.com/ravik775/ticketing/actions/workflows/ci.yml)

A multi-tenant ticket and approval application built the way an enterprise SaaS platform is built:
a Web Application Firewall, an API gateway, OpenID Connect sign-in, mutual TLS between services,
row-level tenant isolation in the database, secrets management, backups with point-in-time recovery,
full observability with security alerting, and a CI/CD pipeline. It runs end to end on one laptop
(k3s via k3d), and every production concern is documented with how it maps to AWS.

---

## At a glance

| | |
|---|---|
| **What it does** | Employees of several companies (tenants) raise tickets; approvers in each company pick them up, lock them and approve, reject or ask for more details |
| **Architecture style** | Modular Spring Boot service behind an edge WAF and API gateway; Zero Trust (every hop authenticated); shared database with row-level security per tenant |
| **Runs on** | Kubernetes (k3s). Locally a single-node k3d cluster; production design for AWS EKS in [docs/07](docs/07-aws-deployment.md) |
| **Quality evidence** | 50 automated tests (0 skipped), 79 live end-to-end checks, 15 observability checks, a restore drill, a k6 load-test baseline (36.6 req/s, p95 109 ms, 0 % errors on a laptop) |
| **Documentation** | an operations handbook for maintainers ([docs/](docs/README.md)), an [enterprise gap assessment](docs/enterprise-gap.md), and an [interview/architecture question bank](interview/README.md) |

### Highlights

* **Zero Trust, layer by layer:** WAF (OWASP CRS) → TLS with certificate verification → API gateway (JWT, per-user rate limit) → **mTLS** with a pinned client identity → the service re-validates the token → **forced PostgreSQL row-level security**. Every layer is tested independently.
* **Correct across two databases:** a **transactional outbox** keeps PostgreSQL (workflow) and MongoDB (documents) consistent without distributed transactions; lost writes are detected and repaired automatically.
* **Noisy-neighbour protection:** per-tenant request and concurrency quotas, database statement timeouts and per-tenant usage metering.
* **Operable:** metrics, logs, traces, SLO burn-rate alerts and a local SIEM (Kubernetes audit, Keycloak events, WAF blocks) with detection rules proven to fire.
* **Recoverable and secret-free:** WAL/oplog backups with a point-in-time restore drill; all credentials in OpenBao (Vault-compatible) and rotated; none in Git.
* **Shippable:** GitHub Actions with a no-skipped-tests gate, vulnerability scanning, SBOM, signed images and an in-pipeline Kubernetes end-to-end test.
* **AI-agent ready:** an **MCP endpoint** (`/api/mcp`) lets assistants raise and decide tickets as the signed-in user, through the same gateway, rules and validation as REST; every history entry records whether it came via REST or MCP.

### Technology stack

| Area | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.5 (Web, Security OAuth2 Resource Server, Data JPA, Data MongoDB, Actuator), Spring AI MCP server (Model Context Protocol), Java Platform Module System, Flyway, Micrometer + OpenTelemetry |
| Frontend | Dependency-free single-page app (vanilla JavaScript, OIDC Authorization Code + PKCE), nginx with a strict Content-Security-Policy |
| Data | PostgreSQL 16 (workflow state, row-level security, transactional outbox), MongoDB 7 (ticket documents and history) |
| Identity | Keycloak 26 (OIDC, Google sign-in brokering, per-tenant groups as roles) |
| Edge and gateway | nginx + ModSecurity v3 + OWASP Core Rule Set 4 (WAF), Kong Gateway OSS 3.9 (DB-less) |
| Platform | Kubernetes (k3s 1.35 via k3d), Kustomize, cert-manager (private CA, mTLS), NetworkPolicies, OpenBao + External Secrets Operator |
| Observability | Prometheus, Alertmanager, Loki, Tempo, Grafana, Grafana Alloy, kube-state-metrics |
| Delivery and testing | GitHub Actions, Maven wrapper, JUnit 5, Testcontainers, Trivy, Syft (SBOM), cosign, k6, Dependabot |

---

## What the application does

* **Applicants** raise tickets (title, mobile number, description) and see only their own. Their email
  and tenant come from the sign-in, never from the form.
* **Approvers** see every ticket of their tenant. They **pick up** a ticket (which **locks** it), then
  *Approve*, *Reject* or *Request more details*, always with a comment. Any approver of the tenant may
  **unlock** a ticket so someone else can take it.
* A person can belong to **several tenants** with a different role in each (switch tenant in the header).
* **Separation of duties:** nobody can approve a ticket they raised, even if they hold both roles
  (enforced in the domain model *and* by a database constraint).
* The UI has two tabs: **Raise ticket** (form and your tickets) and **Approvals** (the tenant queue,
  filtered by status, paged 10/20/50 per page). Every ticket keeps a full, timestamped history.

---

## Architecture

```
                   ┌──────────────────────── Kubernetes cluster (k3s) ────────────────────────┐
Browser ──HTTPS──► │ edge: WAF  (nginx + ModSecurity + OWASP CRS; the ONLY public entry)      │
                   │    │ TLS, Kong's certificate verified                                    │
                   │    ▼                                                                     │
                   │ gateway: Kong OSS  (JWT check, per-user rate limit, size limit)          │
                   │    ├─ /      → ui              (static SPA)                              │
                   │    ├─ /auth  → Keycloak        (OIDC; Google sign-in)                    │
                   │    └─ /api   ──mTLS──► ticket-service  (re-validates JWT, tenant, role,  │
                   │                         │               per-tenant quotas)               │
                   │                         ├─► PostgreSQL  workflow state + RLS + outbox    │
                   │                         └─► MongoDB     ticket details + history         │
                   │ secrets: OpenBao ──► External Secrets ──► Kubernetes Secrets             │
                   │ observability: Prometheus · Loki · Tempo · Grafana · Alertmanager        │
                   └──────────────────────────────────────────────────────────────────────────┘
```

| Namespace | Contents |
|---|---|
| `edge` | WAF (nginx + ModSecurity v3 + OWASP CRS 4), the only externally reachable service |
| `gateway` | Kong OSS (DB-less, declarative configuration) |
| `auth` | Keycloak |
| `ticketing` | ticket-service, ui, PostgreSQL, MongoDB (each database with a backup sidecar) |
| `secrets` / `external-secrets` | OpenBao (secrets manager) and the External Secrets Operator |
| `observability` | Prometheus, Alertmanager, Loki (logs + SIEM rules), Tempo (traces), Grafana, Alloy |

Detailed walkthrough: [docs/04-architecture.md](docs/04-architecture.md).

### Key design decisions

| Decision | Why | Trade-off accepted |
|---|---|---|
| Shared database, tenant per row, **forced row-level security** | lowest cost per tenant; PostgreSQL itself refuses cross-tenant reads even if application code is wrong | noisy neighbours, mitigated with quotas and timeouts; premium tenants could move to separate databases later |
| PostgreSQL for workflow + MongoDB for documents, joined by a **transactional outbox** | atomic, constrained state changes in SQL; flexible ticket content and history as documents | two stores to operate; MongoDB is eventually consistent (milliseconds); [ADR-style discussion in the interview pack](interview/01-Architecture.md) |
| **Zero Trust**: the service re-checks the JWT and requires mTLS from Kong | a compromised or misconfigured gateway cannot impersonate users or skip checks | more certificates to manage (automated by cert-manager) |
| **Java modules (JPMS)** around the domain | the REST layer cannot compile against repositories, so it cannot bypass tenant or role checks | enforced at compile time only (Spring Boot runs on the classpath) |
| **WAF in front of Kong** (positive model: allowed paths, methods, content types) | scanners and generic attacks stop before the gateway; maps to AWS WAF ([docs/09](docs/09-waf-firewall.md)) | false positives must be tuned and documented |
| **Single instances + point-in-time backups** locally | keeps the full stack within ~4.2 GB on a laptop | no high availability locally; production uses managed Multi-AZ databases |

### Security model

| Requirement | How it is met |
|---|---|
| Tenant isolation | Four layers: tenant only from the validated token (`X-Tenant-ID` merely *selects* one the token proves); Hibernate `@TenantId`; PostgreSQL row-level security, **forced** and fail-closed; every MongoDB query filtered by tenant in the one class allowed to touch it |
| Authentication | Keycloak, Authorization Code + **PKCE** (S256), 5-minute RS256 access tokens; the service accepts only access tokens (`typ=Bearer`) issued to the UI or the MCP client (`azp`); MCP-client tokens must also be issued for this API (`aud`) |
| Authorization | per-tenant roles from Keycloak groups `/<tenant>/applicant` and `/<tenant>/approver`; `@PreAuthorize` method security; separation of duties also as a database `CHECK` |
| Service-to-service | WAF→Kong TLS with verification; Kong→service **mTLS**, client certificate pinned to `CN=kong-gateway`; service→Keycloak TLS; all certificates from a cert-manager private CA |
| Network | default-deny ingress **and** egress NetworkPolicies in every namespace, explicit allows only |
| Workloads | non-root, all capabilities dropped, no service-account tokens, read-only root filesystem where possible, images pinned |
| Abuse limits | WAF (OWASP CRS, 1 MB bodies); Kong 300 requests/min **per user** (JWT `sub`); service per-tenant requests/min and concurrency quotas; 5 s SQL statement timeout |
| Admin plane | Keycloak admin console and `master` realm blocked publicly (WAF + Kong, 403); reachable only through `kubectl port-forward` |
| Secrets | OpenBao + External Secrets, one store per namespace, random rotated credentials; nothing sensitive in Git |
| Detection | Kubernetes API audit, Keycloak events, WAF audit log → Loki with alert rules (admin brute force, credential stuffing, privilege change, untrusted workload identity, secret reads, exec) |

Full control catalogue and remaining gaps: [docs/08-security-zero-trust.md](docs/08-security-zero-trust.md).

### Operations features

| Concern | Implementation | Entry point |
|---|---|---|
| Consistency | transactional outbox, idempotent projection, 5 s relay with retries, backlog and dead-event alerts | `ticket-core/.../OutboxPublisher.java` |
| Backups / PITR | PostgreSQL WAL archiving + daily base backup; MongoDB replica set + full dump + 5-minute oplog slices; RPO ≤ 5 min | `scripts/restore-drill.sh` |
| Observability | RED metrics, logs, distributed traces (Kong + service), correlation ID end to end, SLO burn-rate alerts, dead-man's switch | `scripts/verify-observability.sh` |
| Secrets | OpenBao (Raft, TLS, audit device) → External Secrets; one-command rotation | `scripts/secrets-bootstrap.sh --rotate` |
| CI/CD | build + tests (no skips), Trivy gate, SBOM, cosign keyless signing, k3d e2e job; release with a protected environment and digest-pinned deploy | `.github/workflows/` |

---

## Getting started

### 1. Prerequisites

| Tool | Version used | Install |
|---|---|---|
| Docker Desktop (or Docker Engine on Linux) | 29.x | https://docs.docker.com/get-docker/ |
| k3d (runs k3s inside Docker) | 5.9 | Windows `choco install k3d`, macOS `brew install k3d`, Linux `curl -s https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh \| bash` |
| kubectl | 1.33+ | Windows `winget install -e --id Kubernetes.kubectl`, macOS `brew install kubectl`, Linux: https://kubernetes.io/docs/tasks/tools/ |
| JDK | 21 | Windows `winget install -e --id EclipseAdoptium.Temurin.21.JDK`, macOS `brew install openjdk@21`, Linux: your package manager |
| openssl, bash, curl | any recent | included with Git for Windows (Git Bash), macOS and Linux |

Maven is **not** needed: the repository includes the Maven wrapper (`./mvnw`, Maven 3.9.16).

**Machine:** give Docker at least **4 CPUs and 8 GB of memory** (Docker Desktop → Settings → Resources).
The full stack uses about 4.2 GB. **Windows:** run every command from **Git Bash** (or WSL), not
PowerShell or cmd.

### 2. Get the code

```bash
git clone https://github.com/ravik775/ticketing.git
cd ticketing
```

### 3. Create the k3s cluster and deploy everything

One command builds the code, creates the cluster and deploys the whole platform. It is safe to re-run.
The first run takes about 5–10 minutes (mostly image downloads).

```bash
bash scripts/up.sh
```

What `up.sh` does, step by step:

| Step | What happens |
|---|---|
| 1. Cluster | `k3d cluster create ticketing --servers 1 --agents 0 --api-port 127.0.0.1:6550 -p "8443:443@loadbalancer" --k3s-arg "--disable=traefik@server:0"`: a one-node **k3s** cluster in Docker; host port 8443 goes to the cluster's load balancer; the built-in Traefik ingress is disabled so the WAF is the only way in |
| 2. Audit | enables Kubernetes API audit logging on the k3s server (`scripts/enable-k8s-audit.sh`) |
| 3. Build | `./mvnw package`, then builds the `ticket-service` and `ui` images and imports them into the cluster (`k3d image import`) |
| 4. cert-manager | installs cert-manager, which issues every internal certificate from a private CA |
| 5. Secrets | installs the External Secrets Operator and OpenBao, then generates random credentials (`scripts/secrets-bootstrap.sh`). The OpenBao root token is saved **outside** the repository in `~/.ticketing/openbao-init.json` |
| 6. Platform | `kubectl apply -k k8s`: databases, Keycloak (with the demo realm), the service, UI, Kong, WAF, NetworkPolicies and the observability stack |
| 7. Gateway | renders Kong's declarative configuration with Keycloak's signing key and its client certificate (`scripts/render-kong.sh`) |
| 8. Audit events | turns on Keycloak login and admin events (`scripts/configure-keycloak-audit.sh`) |

### 4. Verify the deployment

```bash
kubectl get pods -A                     # everything Running / Completed
bash scripts/e2e.sh                     # 79 checks: WAF, TLS/mTLS, NetworkPolicies, admin lockdown, workflow, MCP
bash scripts/verify-observability.sh    # 15 checks: metrics, logs, traces, alert rules, a simulated attack alert
```

### 5. Open the application

**https://ticketing.localtest.me:8443**. The certificate comes from the cluster's private CA, so
accept the browser warning. (`*.localtest.me` is a public DNS name that points at `127.0.0.1`; no hosts
file edit is needed.)

| User | Password | Role |
|---|---|---|
| alice | `Passw0rd!` | applicant in **acme** *and* **globex** |
| erin | `Passw0rd!` | applicant in acme |
| bob, carol | `Passw0rd!` | approvers in acme |
| dave | `Passw0rd!` | approver in globex |

These demo end-user passwords exist for testing only. Database, Keycloak admin and Grafana passwords are
random and live in OpenBao.

**Try the workflow:** sign in as alice and raise a ticket in acme → in a private window sign in as bob and
pick it up → carol cannot act on it → carol unlocks it, picks it up and approves → alice sees the outcome.

### 6. Admin tools (internal only, through a tunnel)

| Tool | Command | Then open |
|---|---|---|
| Keycloak admin console | `kubectl -n auth port-forward svc/keycloak 9443:8443` | https://localhost:9443/auth/admin/ (user `admin`) |
| Grafana | `kubectl -n observability port-forward svc/grafana 3000:3000` | http://localhost:3000 (user `admin`) |
| Prometheus alerts | `kubectl -n observability port-forward svc/prometheus 9090:9090` | http://localhost:9090/alerts |

Passwords:
```bash
kubectl -n auth get secret keycloak-env -o jsonpath="{.data.KC_BOOTSTRAP_ADMIN_PASSWORD}" | base64 -d; echo
kubectl -n observability get secret grafana-admin -o jsonpath="{.data.password}" | base64 -d; echo
```

### 7. Optional: sign in with Google

Create a *Web* OAuth client in Google Cloud with redirect URI
`https://ticketing.localtest.me:8443/auth/realms/ticketing/broker/google/endpoint`, then:

```bash
bash scripts/set-google.sh <client-id> <client-secret>
bash scripts/add-role.sh <gmail-address> acme applicant     # after the person has signed in once
```

### 8. Stop or remove

```bash
k3d cluster stop ticketing      # pause (keeps data);  k3d cluster start ticketing  to resume
bash scripts/down.sh            # delete the cluster and its data
```

### Using native k3s instead of k3d (Linux, manual)

The scripts target k3d. On a Linux machine with k3s installed natively, the same manifests apply; only
the cluster and image steps differ:

```bash
curl -sfL https://get.k3s.io | sh -s - --disable=traefik          # k3s without Traefik
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
docker save ticketing/ticket-service:dev ticketing/ui:dev | sudo k3s ctr images import -
```

Then run steps 4–8 of `up.sh` by hand. The WAF's LoadBalancer service is published on port **443** of
the host (k3s ServiceLB), so use `https://ticketing.localtest.me/`. This path is documented but not
exercised by the scripts or CI.

---

## Day-to-day commands

| Task | Command |
|---|---|
| Build and run all tests | `./mvnw verify` (needs Docker for Testcontainers; fails if any test is skipped) |
| Redeploy after a code change | `bash scripts/up.sh` (idempotent) |
| Give a user a role | `bash scripts/add-role.sh <email-or-username> <tenant> <applicant\|approver>` |
| Rotate every credential | `bash scripts/secrets-bootstrap.sh --rotate` |
| Point-in-time restore drill | `bash scripts/restore-drill.sh all` or `... postgres "2026-10-05 08:15:00"` |
| Load test (k6 in Docker) | `bash scripts/load-test.sh` |
| Reload renewed certificates | `bash scripts/reload-certs.sh` |
| Follow the WAF log | `kubectl -n edge logs deploy/waf -f` |

Step-by-step guides for maintainers (users and roles, logs, databases, certificates, OpenSSL, AWS,
security, WAF) are in the **[operations handbook](docs/README.md)**.

---

## REST API

All endpoints are under `/api` and need `Authorization: Bearer <access token>`; add `X-Tenant-ID: <tenant>`
if you belong to several tenants.

| Method and path | Who | Purpose |
|---|---|---|
| `GET /me` | any user | email and the tenants/roles proven by the token |
| `POST /tickets` | applicant | raise a ticket `{title, mobile, description}` |
| `GET /tickets?page=&size=`, `GET /tickets/{id}` | applicant | own tickets only (others → 404); `size` 1–200 |
| `POST /tickets/{id}/respond` | applicant | answer a "more details" request |
| `GET /approvals/tickets?status=&page=&size=`, `GET /approvals/tickets/{id}` | approver | all tickets of the tenant |
| `POST /approvals/tickets/{id}/claim` | approver | pick up and lock (409 if locked, 403 on your own ticket) |
| `POST /approvals/tickets/{id}/unlock` | approver | release a lock (403 on your own ticket) |
| `POST /approvals/tickets/{id}/decision` | approver | `{decision: APPROVE\|REJECT\|REQUEST_INFO, comment}`; lock holder only |

Errors use RFC 7807 problem details. Over-quota tenants receive **429** with `Retry-After`. Locks do not
expire by default; set `TICKET_LOCK_TIMEOUT` (e.g. `PT30M`) to allow takeover of old locks (recorded in
the history).

### MCP tools for AI agents (`/api/mcp`)

The same use cases as MCP tools (Model Context Protocol, stateless Streamable HTTP), secured exactly like the
REST API and called with the user's own token (Keycloak client `ticketing-mcp`, PKCE + consent).

| Tool | Same as | Who |
|---|---|---|
| `create_ticket {title, mobile, description}` | `POST /api/tickets` | applicant |
| `decide_ticket {ticketId, decision, comment}` | claim + decision in **one atomic step**; releases another approver's lock first | approver, not on own tickets |
| `get_ticket {ticketId}` | a ticket the caller is permitted to approve | approver |

Details, client set-up and the design decisions (including `/api/mcp` versus `/mcp`): [docs/10](docs/10-mcp-integration.md).

---

## Testing and quality

| Level | What | How |
|---|---|---|
| Unit and slice | domain rules, security filters, tenant resolution, quotas, token client/audience rules | `./mvnw verify` |
| Integration | real PostgreSQL and MongoDB (Testcontainers): row-level security, outbox recovery, full workflow, MCP tools (RBAC, validation, atomic decision, channel audit) | `./mvnw verify` |
| End to end | 79 checks against the running cluster through the WAF: attacks blocked, mTLS enforced, NetworkPolicies, admin lockdown, cross-tenant access refused, the approval workflow, the MCP endpoint | `scripts/e2e.sh` |
| Operability | metrics, logs, traces, alert rules, a simulated attack that must raise an alert | `scripts/verify-observability.sh` |
| Recovery | restore to a point in time in throw-away pods | `scripts/restore-drill.sh` |
| Performance | k6, 20 users: 36.6 req/s, p50 36 ms, p95 109 ms, 0 % errors | `scripts/load-test.sh` |
| Supply chain | Trivy (fails on fixable critical/high), SBOM, cosign signatures, pinned actions, Dependabot | GitHub Actions |

---

## Repository layout

```
pom.xml                 parent build (Spring Boot 3.5, Java 21), Maven wrapper in mvnw / .mvn
ticket-security/        tenant context and role resolution (pure Java module)
ticket-core/            TicketService facade (exported); persistence, outbox, quotas (not exported); Flyway SQL
ticket-api/             REST controllers, Spring Security (JWT, mTLS identity, tenant filter, quotas), Dockerfile
ui/                     single-page app (PKCE login, tabs for raising and approving) + nginx Dockerfile
k8s/                    Kustomize manifests: namespaces, PKI, OpenBao, External Secrets, databases, Keycloak,
                        service, UI, Kong, WAF, NetworkPolicies, observability
scripts/                up/down, e2e, render-kong, secrets-bootstrap, restore-drill, verify-observability,
                        load-test, set-google, add-role, reload-certs
load/                   k6 load-test scenario
.github/                CI (build, scan, e2e, images), release (signed, gated deploy), Dependabot
docs/                   operations handbook, AWS deployment, security, WAF, enterprise gap assessment
interview/              architecture interview question bank built on this system
```

---

## From laptop to production

The local cluster deliberately narrows some concerns to fit on a laptop; each has a documented
production design.

| Concern | Local (this repository) | Production (AWS design, [docs/07](docs/07-aws-deployment.md)) |
|---|---|---|
| Entry and WAF | WAF pod on `localhost:8443`, private CA | Route 53 → ALB with ACM certificate + AWS WAF, in-cluster WAF as a second layer |
| Compute | one k3s node, one replica each | EKS across 3 availability zones, 2+ replicas, autoscaling |
| Databases | single PostgreSQL and MongoDB with PITR backups on the same node | RDS PostgreSQL Multi-AZ, managed document store, cross-account backups |
| Secrets | OpenBao in the cluster | AWS Secrets Manager / KMS via External Secrets |
| Rate-limit counters | per Kong pod | shared store (Redis / ElastiCache) |
| Observability | self-hosted Prometheus, Loki, Tempo, Grafana | managed equivalents, paging to on-call |

**Known limits:** demo end-user passwords and the password grant exist for testing only; database
connections inside the cluster are not encrypted; Kong holds one JWT verification key (re-render after
Keycloak key rotation); JPMS is enforced at compile time. The enterprise readiness scorecard
(3.8 → about 6.2 out of 10) and the ranked list of open gaps are in
[docs/enterprise-gap.md](docs/enterprise-gap.md).
