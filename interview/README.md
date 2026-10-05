# Interview preparation pack: Software & Enterprise Architect (production-focused)

A very-hard question bank built around **this application**: a multi-tenant ticketing platform with a
WAF, Kong, Keycloak, Spring Boot/Spring Security, PostgreSQL + MongoDB on Kubernetes with Zero Trust
controls. Every topic is examined through one lens:

> **How is this aspect *narrowed* for local development, and how does it *unwrap* in production?**

The local k3d deployment in this repository is the **reference implementation and is not changed** by
anything in this pack. Production designs in the answers are **proposals that require architecture
approval**; interviewers expect you to separate "what runs today" from "what I would build".

## Study order

| # | File | Focus |
|---|---|---|
| 01 | [Architecture](01-Architecture.md) | C4, trade-offs, ADRs, failure modes, EA landscape |
| 02 | [Multi-Tenancy](02-Multi-Tenancy.md) | noisy neighbours, month-end peaks, isolation vs shared DB cost/performance, tiers, cells |
| 03 | [Consistency & Replication](03-Consistency-and-Replication.md) | **PACELC, PostgreSQL & MongoDB replication lag** (with a validated lab), read-your-writes, failover |
| 04 | [Data](04-Data.md) | RLS internals, dual write, outbox, erasure, backups |
| 05 | [Security](05-Security.md) | Zero Trust (NIST 800-207), mTLS/PKI, STRIDE, OWASP API Top 10, supply chain |
| 06 | [WAF](06-WAF.md) | ModSecurity + OWASP CRS, positive model, tuning |
| 07 | [Kong](07-Kong.md) | gateway, per-user limits, key rotation, upstream mTLS |
| 08 | [Keycloak](08-Keycloak.md) | OAuth 2.0/OIDC, PKCE, tokens, brokering, revocation |
| 09 | [Spring Security](09-Spring-Security.md) | filter chain, JWT validation, tenant context, method security |
| 10 | [Spring Boot](10-Spring-Boot.md) | JPMS, transactions + RLS binding, migrations, JVM sizing |
| 11 | [Kubernetes](11-K8s.md) | NetworkPolicies, pod security, cert-manager, rollouts |
| 12 | [Scaling](12-Scaling.md) | capacity, availability math, DR, caching, cost |
| 13 | [Observability](13-Observability.md) | SLOs, tracing, dashboards, alerting |
| 14 | [Audit & Alerting](14-Audit-and-Alerting.md) | **breach detection, tamper-evident audit, silent-slip inventory, alerting** |
| 15 | [AI Governance & Observability](15-AI-Governance-and-Observability.md) | **introducing AI**: AI gateway, RAG tenant isolation, prompt injection, GenAI telemetry |
| 16 | [Governance](16-Governance.md) | TOGAF, dispensations, licences, **where governance is imposed** |
| 17 | [Maintainability](17-Maintainability.md) | testing strategy, CI/CD, debt register, upgrades |
| 18 | [Enterprise Integration](18-Enterprise-Integration.md) | customer IdP federation, SCIM, MFA step-up, BYOK, webhooks, incident response |
| 19 | [Behavioural & Leadership](19-Behavioural-and-Leadership.md) | STAR stories from real project events: influence, mistakes, trade-offs |
| 20 | [Back-of-Envelope](20-Back-of-Envelope.md) | worked numeric exercises: connections, storage, token refresh, cost per tenant, error budget |
| – | [PROMPT](PROMPT.md) | the AI mock-interviewer prompt using all files |

## Each file contains

1. **Concepts you must own**: the theory.
2. **How this application applies them**: file-level references.
3. **Local (narrowed) → Production (unwrapped)**: per question: local narrowing, production design,
   how to validate it in production.
4. **Prove it**: commands, run against the local cluster (or a throwaway lab where stated).
5. **Questions** ★★★ hard → ★★★★★ expert, each with *Strong answer*, *Applied here*, *Follow-ups / traps*.

## Environment matrix: local vs production

| Concern | Local k3d (today, unchanged) | Production (target, needs approval) |
|---|---|---|
| Entry & TLS | WAF LoadBalancer on laptop port 8443, private-CA cert, `ticketing.localtest.me` | Route 53 → ALB with ACM public cert + AWS WAF → in-cluster WAF (ClusterIP) |
| Compute | 1 node, 1 replica per component | EKS multi-AZ, ≥ 2 replicas, HPA, PDBs, topology spread |
| Identity | Keycloak 1 replica, demo users, password grant on for tests, events off | Keycloak cluster or enterprise IdP, password grant off, audience mapper, events → SIEM |
| Gateway limits | per-user, `local` counters | per-user + per-tenant, Redis counters, per-IP on login |
| PostgreSQL | single instance (by decision) + WAL archiving, one base backup, PITR restore drill; 5 s statement timeout; app owns tables | RDS Multi-AZ, optional read replica, PITR, separate owner/app roles, per-tier limits |
| MongoDB | single instance as a 1-member replica set (oplog, change streams) + oplog backups, PITR restore drill | 3-member replica set / Atlas / DocumentDB, `w:majority`, backups |
| Cross-store consistency | transactional outbox + relay + metrics/alerts (implemented) | transactional outbox + reconciliation alerts |
| Certificates | cert-manager private CA, manual `reload-certs.sh` | ACM at the edge, short-lived internal certs with automatic reload |
| Secrets | OpenBao + External Secrets, random rotated credentials, none in Git (implemented; gap closed) | Secrets Manager + External Secrets, KMS-encrypted etcd |
| Admin console | blocked at WAF + Kong; internal via kubectl port-forward (implemented; gap closed) | internal-only access (VPN/internal ALB) |
| Observability | Prometheus, Grafana, Loki, Tempo, Alertmanager; SLO burn-rate alerts; traces Kong â service (implemented) | OTel traces, Prometheus/Grafana or CloudWatch, SLO alerts, SIEM |
| Audit | Kubernetes API audit + Keycloak events + OpenBao audit in Loki (local SIEM) with detections (implemented) | tamper-evident central audit, k8s audit, pgaudit, Keycloak events |
| Governance | GitHub Actions CI/CD: no-skip gate, CVE/secret scans, SBOM, signing, k3d e2e (implemented, not yet run on GitHub) | CI gates, admission policies, ARB for deviations, dispensation register |
| AI | none | AI gateway, eval gates, use-case register (proposal) |

## Coverage map for frequently asked hard topics

| Topic | Where |
|---|---|
| PACELC; how replication lag is handled with PostgreSQL & MongoDB | 03 (Q1–Q7, lab), 12 Q6 |
| Noisy tenant; tenant with monthly high volume | 02 Q1, Q3–Q5; 12 Q3 |
| Isolation vs sharing one DB (cost, performance, isolation) | 02 Q2–Q3, Q6; 01 Q2; 04 Q7, Q9 |
| Where and how governance is imposed | 16 ("Where governance is imposed" table, Q2, Q4); 17 Q6 |
| AI governance and AI observability in this architecture | 15 (all) |
| How audit captures a breach; alerting instead of silent slips | 14 (all); 13 Q5, Q9; 05 Q10 |

## Ground truth about the system (memorise)

| Item | Value |
|---|---|
| Entry | `https://ticketing.localtest.me:8443` → WAF (`edge`) → Kong (`gateway`, ClusterIP) |
| Versions | k3s 1.35, Kong OSS 3.9 (DB-less), Keycloak 26.3, Spring Boot 3.5 / Java 21, PostgreSQL 16, MongoDB 7, OWASP CRS 4.30 / ModSecurity 3.0.17, cert-manager 1.18 |
| Identity | realm `ticketing`, public client `ticketing-ui`, Auth Code + PKCE S256, access token 300 s, `groups` claim `/<tenant>/<role>`, **no `aud` claim** (service checks `azp`) |
| Zero Trust hops | Browser→WAF TLS; WAF→Kong TLS verified; Kong→API **mTLS** + CN pin `kong-gateway`; API re-validates JWT (`iss`, `exp`, `typ=Bearer`, `azp`) |
| Data | PostgreSQL: forced, fail-closed RLS, single instance + WAL archive/PITR, 5 s statement timeout, transactional outbox; MongoDB: 1-member replica set + oplog backups, tenant filter in one class |
| Silent slips closed | Mongo history loss (outbox + idempotent projection, rebuild test); skipped tests (CI fails on skips); cert reload still manual (`scripts/reload-certs.sh`) but now covered by `CertificateExpiresSoon`/`CertificateNotReady` alerts; Keycloak events + k8s audit now on, shipped to Loki with alert rules |
| Tests | `mvn verify` (43 tests incl. Testcontainers, 0 skipped), `scripts/e2e.sh` (56 live checks), `scripts/verify-observability.sh` (15 checks), `scripts/restore-drill.sh` (PITR) |
| Accepted gaps | none open: both former gaps (demo passwords; admin console via WAF) were closed on 2026-10-05 |

## How to study

1. Read *Concepts*, answer each question **aloud** before reading the model answer.
2. For every answer, state both halves: *"Locally we narrowed it to X because…; in production it
   unwraps to Y, and I would validate it with Z."*
3. Run the *Prove it* commands; for 03, run the lab (throwaway containers, delete afterwards).
4. Use [PROMPT.md](PROMPT.md) for a timed mock interview with an AI assistant.
5. Structure: **context → options → decision → trade-off → validation → what changes at 10× / in production.**
