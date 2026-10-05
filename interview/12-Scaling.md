# Scaling, performance, availability, disaster recovery and cost

## Concepts you must own

* **Scalability dimensions:** vertical vs horizontal; stateless tiers scale out, stateful tiers need
  replication/partitioning; scale cube (X clone, Y functional split, Z data partition by tenant).
* **Little's Law:** concurrency = throughput × latency: sizes thread pools, connection pools, replicas.
* **Bottleneck analysis:** USE (utilisation, saturation, errors) for resources; RED (rate, errors,
  duration) for services; Amdahl: the serial part (database, locks) caps speed-up.
* **Availability math:** serial dependencies multiply (0.999 × 0.999 = 0.998); redundancy adds
  parallel paths; error budgets (99.9 % = 43.2 min/month).
* **Resilience patterns:** timeouts, retries with backoff + jitter, circuit breakers, bulkheads, load
  shedding, idempotency, graceful degradation.
* **Caching:** what, where (client, gateway, service, DB), invalidation, multi-tenant cache keys.
* **DR:** RPO (data you can lose) / RTO (time to restore); backup/restore, pilot light, warm standby,
  active-active.
* **Cost/FinOps:** unit economics (cost per tenant/ticket), right-sizing, autoscaling, managed vs self-run.

## How this application stands today

| Tier | Scales how | Stateful? | Current limit |
|---|---|---|---|
| WAF | horizontally (stateless) | no | 1 replica; CPU-bound on TLS + rule evaluation |
| Kong | horizontally | rate-limit counters (`local`) | 1 replica, `KONG_NGINX_WORKER_PROCESSES=1` |
| Keycloak | horizontally with Infinispan cluster | sessions in cache, data in DB | 1 replica, no cache cluster |
| ticket-service | horizontally (stateless; JWT local validation) | no | 1 replica; DB pool size default (Hikari 10) |
| PostgreSQL | vertical + read replicas + partitioning | yes | single pod, local-path volume; WAL archive + one base backup |
| MongoDB | replica set / sharding | yes | single pod, no replica set |

Design properties that already help: stateless service and gateway; local JWT validation (no
per-request call to Keycloak); optimistic locking (no long DB locks); indexes leading with `tenant_id`;
bounded page size (≤ 200); per-user rate limit; 1 MB body cap.

## Measured baseline (k6, local laptop, 2026-10-05)

Run with `scripts/load-test.sh` (k6 in Docker; temporary `loadtest` tenant with 20 applicant + 20
approver identities, one per virtual user; all test data removed afterwards). 2 m 45 s, ramping to
**20 concurrent users** (10 applicants creating and listing tickets, 10 approvers listing, claiming and
approving), 1 s think time, full path **WAF → Kong → ticket-service → PostgreSQL + MongoDB**, one replica each.

| Measure | Result |
|---|---|
| Requests | 6,335 (36.6 req/s), **0 % errors**; 46 expected lock conflicts (409) between approvers |
| End-to-end latency (API) | p50 **36 ms**, p95 **109 ms**, p99 **176 ms**, max 426 ms |
| Create ticket | p50 40 ms, p95 108 ms (Postgres + outbox + Mongo projection in the request) |
| Approver queue (20 items) | p50 32 ms, p95 135 ms |
| Claim + decide (2 calls) | p50 84 ms, p95 181 ms |
| Data integrity | 1,304 tickets created, 1,198 approved, **outbox backlog 0** at the end |
| Peak CPU / memory | Keycloak 1.78 cores (the 40 logins at test start) / 772 MiB; ticket-service 0.48 / 494 MiB; WAF 0.37 / 81 MiB; Kong 0.21 / 276 MiB; PostgreSQL 0.17 / 70 MiB; MongoDB 0.16 / 250 MiB |

How to use these numbers in an interview: this is a **laptop baseline, not capacity**. It shows where
CPU goes per request (the service, then the edge) and that the databases are far from saturation at this
load; Little's Law at 36.6 req/s × ~0.05 s ≈ **2 requests in flight**. The next step is a step-load test to
find the knee (latency vs throughput) per replica, then size replicas, pools and database from it. A
first, invalid run taught a lesson worth telling: k6 numbers virtual users across scenarios, two VUs
shared one identity, and Kong's per-user limit returned 429s; the system was right, the test was wrong.

## Local (narrowed) → Production (unwrapped)

> Local design unchanged; production items are **proposals requiring approval**.

| Q | Aspect | Local (narrowed) | Production (unwrapped) | Validate in production |
|---|---|---|---|---|
| Q1 | 100 → 100 000 tenants | single replicas, laptop CPU | replicas + HPA, Redis limits, PgBouncer/replicas, Mongo sharding, Keycloak cluster, multi-node | staged load tests per milestone |
| Q2 | Availability | ≈ product of single pods | redundant tiers + Multi-AZ DBs; SLO-based | measured SLO attainment |
| Q3 | System protection | per-user limit only | per-IP (edge), per-tenant, global concurrency, load shedding | overload test shows 503s not timeouts |
| Q4 | Real-time updates | polling | SSE/WebSocket via event stream | connection-count and fan-out tests |
| Q5 | Caching | JWKS only | CDN for UI, tenant-keyed private caches | cache-leak test across tenants |
| Q6 | DR | none (laptop) | PITR, cross-region copies, warm standby, DNS failover | DR drill hits RPO 5 min / RTO 1 h |
| Q7 | Connections | defaults, 1 replica | PgBouncer, pool sizing from Little's Law, Keycloak pool tuned | `pg_stat_activity` headroom alert |
| Q8 | Autoscaling signal | none | concurrency/latency-based for the API; CPU for TLS tiers | scale events vs SLO |
| Q9 | Cost | laptop | unit cost per tenant, Graviton, Savings Plans | monthly FinOps review |
| Q10 | Degradation | implicit | explicit degraded modes + status page | chaos tests per dependency |

## Prove it

```bash
kubectl top pods -A --sort-by=cpu
kubectl -n ticketing describe pod -l app=ticket-service | grep -A3 -E 'Limits|Requests'
# crude load probe (sequential, measures per-request latency through all layers)
T=$(curl -sk https://ticketing.localtest.me:8443/auth/realms/ticketing/protocol/openid-connect/token -d grant_type=password -d client_id=ticketing-ui -d username=bob -d 'password=Passw0rd!' -d scope=openid | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)
for i in $(seq 1 20); do curl -sk -o /dev/null -w '%{time_total}\n' -H "Authorization: Bearer $T" "https://ticketing.localtest.me:8443/api/approvals/tickets?size=50"; done | sort -n | awk '{a[NR]=$1} END {print "p50="a[int(NR*0.5)]" p95="a[int(NR*0.95)]}'
kubectl -n ticketing exec postgres-0 -- psql -U postgres -d ticketing -c "EXPLAIN ANALYZE SELECT * FROM ticket_workflow WHERE tenant_id='acme' AND status='OPEN' ORDER BY created_at DESC LIMIT 50;"
```

(For real load tests use k6 or Gatling with many users: rate limits are per user, so a single token
hits 429 at 300 requests/minute.)

---

## Questions

### Q1. Take this system from 100 to 100 000 tenants and 5 000 requests/second. What breaks first, in order? ★★★★★
**30-second headline:** In order: single replicas and gateway CPU, per-pod rate counters, database connections and OFFSET paging, Mongo document growth, Keycloak refresh load, single node. Quantify each with Little's Law against a measured baseline.
**Weak answer (what fails):** "Add more pods" without naming the database as the real limit.
**Would I do it again?** I'd build the load test into CI before scaling, so every bottleneck claim has a number behind it.
**Since implemented:** a measured k6 baseline now exists (see "Measured baseline" above).
**Strong answer:** (1) Single replicas everywhere: no HA, CPU saturation at the WAF/Kong (TLS + Lua)
→ replicas + HPA. (2) Rate limiting goes wrong with replicas (`local`) → Redis. (3) PostgreSQL: list
queries with OFFSET paging and Mongo `$in` with 200 ids per page; connection count (replicas × pool
size) exceeds `max_connections` → keyset paging, PgBouncer, read replicas for approver lists. (4) Mongo
document growth (embedded events) and `$in` batch reads → bucket history, project summaries, replica
set → sharding on `{tenantId, _id}`. (5) Keycloak: login/refresh storms (every user refreshes every
~4.5 min: 100 k users ≈ 370 refresh/s) → Keycloak cluster, longer access token or smarter refresh,
DB tuning. (6) Kubernetes: single node → multi-node, multi-AZ.
**Follow-ups / traps:** Quantify: "5 000 rps × 50 ms DB time ≈ 250 concurrent DB operations" (Little's
Law) → pool sizes and DB cores.

### Q2. Calculate end-to-end availability of the request path today and with 2 replicas per tier. ★★★★
**30-second headline:** Serial chain multiplies: five single pods at 99.5 % ≈ 97.5 %; with redundant stateless tiers and Multi-AZ databases ≈ 99.9 %, dominated by the databases and by change-related outages.
**Weak answer (what fails):** Adding availabilities instead of multiplying.
**Strong answer:** Serial chain: WAF, Kong, ticket-service, PostgreSQL, MongoDB (+ Keycloak for
login). If each single pod is 99.5 % available: 0.995⁵ ≈ 97.5 % for API calls. With two replicas per
stateless tier (1 − 0.005² ≈ 99.9975 % each) and managed Multi-AZ DBs (~99.95 % each):
≈ 0.99997³ × 0.9995² ≈ 99.89 %. The databases dominate; also count deploys, certificate expiry and
human error, which usually dominate in practice. Write availability targets per user journey (login vs
API).

### Q3. Kong rate limiting is per user. Design capacity protection for the *system*, not just the user. ★★★★
**30-second headline:** Layered protection: per-IP at the edge for login, per-user at Kong, per-tenant in the service, global concurrency/load shedding before the database, priority for interactive actions.
**Weak answer (what fails):** One global rate limit.
**Since implemented:** per-tenant request and concurrency quotas are implemented.
**Strong answer:** Layers: per-IP limits at the edge for unauthenticated endpoints (login/token); per
user (have); **per tenant** (noisy-neighbour control: a second rate-limiting dimension, or a quota
service); **global concurrency limit** at Kong/service (bulkhead) with load shedding (503 + Retry-After)
before the DB saturates; Hikari pool as the final bulkhead. Priority classes: approver actions over
list refreshes.

### Q4. The approval queue polls. How would you design real-time updates at scale? ★★★★
**30-second headline:** Server-Sent Events or WebSockets from a notification service fed by outbox events, tenant-scoped subscriptions, long-lived-connection support at WAF and Kong; ETag polling as a first step.
**Weak answer (what fails):** "Poll faster."
**Strong answer:** Server-Sent Events or WebSockets from a notification service fed by an event stream
(outbox → Kafka/SNS) with tenant-scoped channels; the WAF/Kong must support long-lived connections
(timeouts, Upgrade headers; Kong supports WebSocket routes); authorisation per subscription (tenant +
role); fan-out with backpressure. Simpler first step: conditional GET with ETag/`If-None-Match`, or
long polling.

### Q5. What would you cache, where, and what are the multi-tenancy traps? ★★★★
**30-second headline:** Cache static UI and keys freely; cache ticket data only privately with tenant + user + role in the key; a cache key missing the tenant leaks data across tenants.
**Weak answer (what fails):** Caching API responses at the gateway.
**Strong answer:** Static UI at the CDN/browser (immutable hashed file names). JWKS (already cached by
Nimbus). Tenant registry lookups (tiny, rarely changes). *Not* ticket data at the gateway: responses
depend on user and tenant; a cache key missing `Authorization`/`X-Tenant-ID` would leak data across
tenants: the classic multi-tenant cache bug. If caching API responses: private caches only, keys
include tenant + user + role, short TTLs, invalidation on writes.

### Q6. Define RPO/RTO for this system and the DR architecture to meet RPO 5 min / RTO 1 h. ★★★★
**30-second headline:** RPO 5 min / RTO 1 h needs continuous WAL/oplog backup, both stores restorable to the same time, infrastructure as code, a warm standby or pilot light, DNS failover and practised drills; keys and secrets must be restorable too.
**Weak answer (what fails):** "We take nightly backups."
**Would I do it again?** Yes; the local restore drill proved the procedure, which is the part most teams never test.
**Since implemented:** locally RPO ≤ 5 min with WAL archiving + oplog slices; restore drill passes.
**Strong answer:** Stores: Postgres (workflow + Keycloak), Mongo (details). RPO 5 min: continuous WAL
archiving / PITR (RDS) + Mongo continuous backups / Atlas; cross-region snapshot copies. RTO 1 h:
infrastructure as code for the platform (EKS + manifests), warm standby or pilot light in a second
region, DNS failover (Route 53 health checks), runbook, quarterly drill. Consistency between the two
stores at restore time: restore to the same timestamp, then run reconciliation (`docs/03` §3.4) or
replay the outbox. Secrets/keys (CA, Keycloak realm keys) must be restorable too: otherwise all tokens
and mTLS break after failover.

### Q7. Connection management: 10 replicas × Hikari 10 = 100 connections. Is that fine? What else connects? ★★★★
**30-second headline:** PostgreSQL defaults to 100 connections, and Keycloak's pool plus every service replica compete for them; size pools from Little's Law and put PgBouncer in front.
**Weak answer (what fails):** "100 is enough."
**Strong answer:** Postgres default `max_connections` is 100, and Keycloak also holds a pool (default
up to 100 in Quarkus/Agroal config: tune it), plus migrations and admin sessions → exhaustion. Use
PgBouncer (transaction pooling works with our transaction-local `set_config`), size pools from Little's
Law rather than defaults, and monitor `pg_stat_activity`.

### Q8. Autoscaling ticket-service on CPU: good idea? ★★★
**30-second headline:** The service is I/O-bound: CPU may stay low while latency explodes, and more pods add database load; scale on concurrency/latency with a ceiling from database capacity.
**Weak answer (what fails):** "Yes, standard practice."
**Strong answer:** It's I/O-bound; CPU may stay low while latency explodes because the DB is the
bottleneck: scaling pods then *adds* DB load. Better signals: request concurrency / p95 latency (KEDA,
custom metrics), with a max replica count derived from DB capacity. Scale Kong/WAF on CPU (TLS-heavy).

### Q9. Cost: what dominates the AWS bill for this design and how would you reduce it? ★★★★
**30-second headline:** Managed databases, nodes, NAT data processing, load balancer + WAF requests and log ingestion dominate; cut with one store, Graviton, right-sizing, VPC endpoints and log tiering, tracked as cost per tenant.
**Weak answer (what fails):** Talking only about compute.
**Strong answer:** Managed databases (RDS Multi-AZ, DocumentDB instances), EKS nodes, NAT gateway data
processing, ALB + WAF requests, logs ingestion. Reductions: one store (JSONB) removes DocumentDB;
Graviton instances; right-size from metrics; Savings Plans; VPC endpoints to cut NAT traffic; log
sampling/retention tiers; scale-to-minimum outside business hours for non-prod. Track unit cost per
tenant per month: essential for SaaS pricing.

### Q10. Graceful degradation: which features can fail without failing the core journey? ★★★★
**30-second headline:** MongoDB down: approvals still work and history catches up via the outbox; Keycloak down: existing sessions continue until token expiry; Google down: local login works.
**Weak answer (what fails):** "Everything is critical."
**Since implemented:** MongoDB-down now degrades to delayed history instead of lost history.
**Strong answer:** If Mongo is down: approvals still work (Postgres), history append fails (logged),
details show "(details unavailable)": degraded but functional. If Keycloak is down: current sessions
work until token expiry, no new logins. If Google is down: local login still works. Design explicit
degradation modes (feature flags, cached reads) and communicate them in the status page.

### Q11. Rapid fire
* Why does local JWT validation matter for scale? → no per-request call to Keycloak.
* Worst-case Kong limit with 3 replicas and `local` policy? → 3 × 300/min per user.
* Max page size? → 200 (bounds the Mongo `$in` and payloads).
* What is shared state in the API tier? → none (tenant context per request), so it scales horizontally.
* Biggest SPOF today? → every component has 1 replica (by decision); data is recoverable from backups (RPO ≤ 5 min) but not highly available.
