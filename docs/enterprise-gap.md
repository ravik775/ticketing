# Enterprise gap assessment

This page records how the application scored against an **enterprise-scale production** bar in the
architecture review of 2026-10-05, what has been fixed since, and what is still open.

Read it with one distinction in mind: the k3d deployment is a **reference design** that runs on a
laptop. It is judged here *as if* it were going to production, so "single replica" counts against it
even where single replicas were a deliberate choice to save resources.

---

## 1. Original assessment (before remediation)

| Area | Score /10 | What convinced the reviewer | What didn't |
|---|---|---|---|
| Security architecture | **7.0** | Independent layers that really are independent: verified TLS, mTLS with a pinned caller, the service re-checking the JWT, forced fail-closed row-level security, default-deny network rules, all proven with tests. | No `aud` claim; identity keyed on email; any namespace can mint a `kong-gateway` certificate; 90-day certificates with no revocation; password grant enabled; two accepted gaps. |
| Identity (Keycloak) | **6.0** | PKCE S256, short tokens, the `typ`/`azp` checks; group-per-tenant fits users in several tenants. | No joiner-mover-leaver process, no SCIM, no MFA, no audit events, a single signing key never rotated, a single replica. No answer to "how do I connect *my* Entra ID?" |
| Multi-tenancy | **5.0** | Data isolation is strong (four layers, tested). | **Zero performance isolation:** no per-tenant quota, unlimited query time, no connection caps, no metering. One month-end tenant takes everyone down, and you can't see which tenant it was. |
| Gateway (Kong OSS) | **5.0** | The `pre-function` trick for per-user limits is clever; plugin-order reasoning is sound. | A workaround for an edition limit. A static signing key means key rotation causes an outage; counters are per pod; no upstream health checking. |
| Data & consistency | **3.5** | Optimistic locking, constraints as executable rules, a test proving PostgreSQL itself isolates tenants. | Two stores written separately with no outbox; **history can vanish without any log line**; MongoDB standalone; no replicas, no backups; history arrays grow without limit. Go-live blocker on its own. |
| Availability & scalability | **2.0** | Stateless service tier; JWTs validated locally. | One replica of everything, one node, no failure-domain separation, no load test. |
| Observability | **2.5** | Correlation ID from the WAF to the client; WAF audit log without credentials. | No metrics history, no traces, no SLOs, no alerts. The service doesn't log the correlation ID. |
| Audit & breach detection | **2.0** | An honest inventory of 10 silent failures. | Inventory isn't detection: Keycloak events off, Kubernetes audit off, no SIEM, nothing tamper-evident. |
| Platform (Kubernetes) | **6.0** | Pod hardening enforced (read-only filesystem, no service-account token, non-root); certificates automated. | Single node, no GitOps, no admission policies, config changes that don't restart pods, probes that only test a TCP port. |
| Delivery (CI/CD) | **1.5** | `mvn verify` and 53 e2e checks exist. | **No pipeline.** Every test run by hand; integration tests once skipped silently. |
| Governance | **3.5** | A risk register with accepted gaps; runbooks verified command by command. | No ADRs, no risk owners, no expiry triggers, no licence review (MongoDB SSPL), no data classification. |
| Operations, DR, cost | **1.5** | `reload-certs.sh` and the runbooks show operational care. | No backups, no RPO/RTO, no restore ever tested, no cost model, no on-call process. |
| Code & maintainability | **7.0** | Clean module boundary, business rules in one place, readable code, good tests per layer. | Persistence errors swallowed; string-templated YAML (it broke Kong once); no pinned build toolchain. |

**Overall enterprise production readiness: 3.8 / 10.** As a reference design for Zero Trust
multi-tenant SaaS: **7.5 / 10**.

### Sign-off conditions set by the review

1. Transactional outbox with reconciliation and an alert.
2. Per-tenant quotas, statement timeouts and metering.
3. Backups with a restore drill and a measured RPO/RTO.
4. Metrics, traces, SLOs and burn-rate alerts, with the correlation ID in service logs.
5. Keycloak events and Kubernetes audit shipped to a SIEM, with detection rules proven to fire.
6. A CI/CD pipeline that fails on skipped tests and gates on vulnerabilities.
7. Close the two accepted gaps (demo passwords in Git, admin console reachable through the WAF).

---

## 2. Remediation status (implemented on local k3d)

All seven conditions were implemented on 2026-10-05 and verified on the local cluster.

| # | Condition | What was built | Evidence |
|---|---|---|---|
| 1 | Outbox | `ticket_outbox` table written in the same transaction as the workflow (V4 migration); relay every 5 s with retries and dead events; idempotent MongoDB projection keyed on event ID; projection throws if the document is missing; 30-day purge | Integration test `lostProjectionIsDetectedAndRebuiltFromTheOutbox`; metrics `ticketing_outbox_*`; alerts `OutboxBacklogGrowing`, `OutboxEventsDead`, `OutboxPublishFailures` |
| 2 | Tenant isolation (performance) | Per-tenant requests/minute and concurrency quotas (429 + `Retry-After`); `statement_timeout` 5 s and `idle_in_transaction_session_timeout`; daily `tenant_usage` metering plus per-tenant counters | `ApiSecurityTest` quota test; `TenantThrottled` alert; `select * from tenant_usage` |
| 3 | Backups & PITR | PostgreSQL: WAL archiving + daily base backup. MongoDB: 1-member replica set, full dump + 5-minute oplog slices. One backup copy kept per database (resource decision) | `scripts/restore-drill.sh all` restores into throwaway pods and proves data after the target time is excluded; `BackupTooOld`, `BackupFailing`, `PostgresWalArchivingFailing` alerts. RPO ≤ 5 min; RTO measured in minutes for the local data size |
| 4 | Observability | Prometheus, Alertmanager, Loki, Tempo, Grafana 11.6, Alloy, kube-state-metrics, all with CPU/memory limits; Kong Prometheus + OpenTelemetry plugins; Actuator on a separate port with HTTP probes; OTLP traces; correlation ID in the MDC; SLO burn-rate alerts; `Watchdog` dead-man's switch | `scripts/verify-observability.sh` (15/15) |
| 5 | Audit & detection | Kubernetes API audit policy; Keycloak user and admin events; Loki as the local SIEM with LogQL rules (master-realm login failures, credential stuffing, privilege change, untrusted workload identity, WAF block spike, secrets read by a human, exec/port-forward, audit log silent) | Master-realm brute-force alert simulated and seen firing (`verify-observability.sh`) |
| 6 | CI/CD | GitHub Actions with SHA-pinned actions and the Maven wrapper; build fails on any skipped test; Trivy gates fixable CRITICAL/HIGH; SBOM; cosign keyless signing; k3d end-to-end job; release workflow with a protected environment and digest-pinned deploy; Dependabot | `actionlint` clean; 7 critical CVEs fixed by patch upgrades (Spring Boot 3.5.16, Tomcat 10.1.60, pgjdbc 42.7.12, Jackson 2.21.7). **Not yet run on GitHub** |
| 7 | Accepted gaps | Credentials moved to OpenBao, delivered by External Secrets with per-namespace stores; all demo passwords rotated to 32-character random values (`secrets-bootstrap.sh --rotate`). Admin endpoints blocked at WAF (rule 1000100) and Kong; admin console only via `kubectl port-forward` | `scripts/e2e.sh` 56/56 including 3 admin-lockdown checks; cross-namespace secret read denied |

Additional fixes made along the way: 43 Java tests with 0 skipped; a k6 load-test baseline
(36.6 req/s, p95 109 ms, 0 % errors with 20 users); the node uses ~4.2 GB of its 8 GB with the full stack.

---

## 3. Re-assessment (after remediation)

Scores are re-rated against the same enterprise bar. Single-instance topology is still scored as a gap,
because it was a deliberate local choice, not a production design.

| Area | Before | After | Why it moved / why not further |
|---|---|---|---|
| Security architecture | 7.0 | **7.5** | Secrets out of Git and rotated; admin plane closed. Still no `aud`, password grant on for tests, no certificate revocation. |
| Identity (Keycloak) | 6.0 | **6.5** | Events on and alerted. Still no SCIM, MFA, per-tenant federation, key rotation or replicas (designs in `interview/18`). |
| Multi-tenancy | 5.0 | **7.0** | Quotas, timeouts and metering. Quotas are per pod (in-memory) and there are no tiers or cells. |
| Gateway (Kong OSS) | 5.0 | **5.5** | Metrics and traces added. Still a static JWT key, per-pod counters, no upstream health checks. |
| Data & consistency | 3.5 | **6.5** | Outbox, idempotent projection, PITR, restore drill. Still single instances and unbounded history arrays. |
| Availability & scalability | 2.0 | **3.0** | Measured baseline exists. Still one replica and one node; no step-load test to find the knee. |
| Observability | 2.5 | **7.0** | Metrics, logs, traces, SLOs, burn-rate alerts and a dead-man's switch. No external paging target yet. |
| Audit & breach detection | 2.0 | **6.5** | Kubernetes and Keycloak audit in a SIEM, rules proven. Not tamper-evident (no WORM storage), local retention only. |
| Platform (Kubernetes) | 6.0 | **6.5** | HTTP probes, ESO, OpenBao. Still single node, no GitOps, no admission policies. |
| Delivery (CI/CD) | 1.5 | **6.5** | Full pipeline with gates, SBOM and signing. Unproven until it runs on GitHub; no policy check on signatures at admission. |
| Governance | 3.5 | **4.5** | Gaps closed with evidence, decisions documented. Still no ADR set, risk owners or data classification. |
| Operations, DR, cost | 1.5 | **5.0** | Backups, PITR drill, rotation runbook, cost model (`interview/20` E4). No on-call process, no off-site backup copy. |
| Code & maintainability | 7.0 | **7.5** | Persistence errors no longer swallowed; Maven wrapper. String-templated Kong YAML remains. |

**Overall enterprise production readiness: about 6.2 / 10** (from 3.8). The remaining distance is
mostly topology (replicas, multiple nodes and zones) and enterprise integration, which need approval
and resources rather than code fixes.

---

## 4. Still open (ranked)

| Priority | Gap | Proposed fix | Needs |
|---|---|---|---|
| 1 | Single instances, single node | Production: RDS Multi-AZ, managed document store, 3 AZs, ≥2 replicas per service (see `docs/07-aws-deployment.md`) | Approval + cloud budget |
| 2 | Off-site backup copy | Copy WAL/base backups and oplog slices to object storage with object lock (S3, separate account) | Approval |
| 3 | CI/CD not yet run on GitHub | Push and observe the first run, including the k3d e2e job | Push approval |
| 4 | Tenant quotas per pod | Move counters to Redis/ElastiCache, or enforce at Kong with a shared store | Design approval |
| 5 | Enterprise identity | Per-tenant IdP federation, SCIM, MFA step-up, `aud` claim, password grant off outside tests | Design approval |
| 6 | Tamper-evident audit | Ship audit logs to WORM storage with retention ≥ 1 year | Approval + storage |
| 7 | Admission control | Kyverno/Gatekeeper: signed images only, pod-security baseline | Approval |
| 8 | Governance artefacts | ADRs for the main decisions, risk owners and expiry dates, data classification, MongoDB SSPL review | Owner time |
| 9 | Unbounded history arrays | Bucket events or move history to a separate collection | Design approval |
