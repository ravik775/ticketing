# Multi-tenancy: noisy neighbours, peak tenants, isolation vs shared resources

## Concepts you must own

* **The tenancy triangle:** *isolation* (security, performance, failure, compliance), *cost* (shared
  infrastructure is cheaper per tenant), *operability* (fewer deployments are easier). You can
  optimise two; tiering lets different tenants sit at different points.
* **Isolation is several things:** data isolation (who can read what), **performance isolation**
  (one tenant's load does not degrade others), failure isolation (blast radius), operational
  isolation (per-tenant backup/restore, maintenance windows), compliance isolation (residency, keys).
* **Models:** pool (shared everything, row-level tenant id), bridge (shared compute, schema or
  database per tenant), silo (dedicated stack). **Cell-based architecture**: N identical pool "cells",
  each serving a bounded set of tenants; tenants can be moved between cells.
* **Noisy neighbour controls, at every layer:** admission (rate limits/quotas per tenant), work
  queues with fair scheduling, concurrency limits (bulkheads), resource limits in the database
  (connections, statement timeouts, work memory), data layout (partitioning), and placement (move or
  isolate heavy tenants).
* **Predictable peaks** (month-end, quarter-end, payroll runs): capacity reservation and scheduled
  scaling beat reactive autoscaling; shift batch work off the interactive path.
* **Cost allocation:** measure consumption per tenant (requests, CPU-seconds, storage, DB time) to
  price tiers and detect abuse: "unit economics per tenant".

## Where this system sits today

| Layer | Tenant-aware today | Gap |
|---|---|---|
| Edge (WAF) | no (per-request inspection only) | per-tenant/IP limits impossible on k3d (masqueraded IPs) |
| Kong | **per user** rate limit (300/min, JWT `sub`) | no **per-tenant** quota; `local` counters per pod |
| Service | tenant context; page size ≤ 200; **per-tenant requests/minute and concurrency quota** (`TenantQuotaFilter`) | in-memory counters (one replica); no priority classes |
| PostgreSQL | RLS isolates data; indexes lead with `tenant_id`; **5 s statement timeout, 30 s idle-in-transaction timeout** on app connections | one pool, no per-tier roles or partitioning |
| MongoDB | tenant filter on every query; index `{tenantId, createdBy}`; 5 s `maxTime` | single instance, no sharding, unbounded `events` arrays |
| Cost | **daily metering per tenant** (`tenant_usage`) + Prometheus counters | no cost allocation yet |

## Local (narrowed) → Production (unwrapped)

> Local design unchanged; production items are **proposals requiring approval**.

| Q | Aspect | Local (narrowed) | Production (unwrapped) | Validate in production |
|---|---|---|---|---|
| Q1 | Month-end peak | 2 tenants, no load; per-user limit only | capacity calendar (scheduled scale), per-tenant quotas, async bulk work, time/tenant partitioning | load test replaying a month-end profile; per-tenant p95 during peak |
| Q2 | Tiers | single pool | standard pool cells, business (bridge/partitions), enterprise silo | tier recorded in tenant registry; routing tests per tier |
| Q3 | DB knobs | 5 s statement timeout (all app connections), no per-role connection limits | per-tier roles with connection limits and timeouts, PgBouncer pools per tier, partitions | `pg_roles.rolconfig`, PgBouncer `SHOW POOLS`, `pg_stat_statements` per tenant |
| Q4 | Mongo noisy neighbour | single instance, one collection, 5 s maxTime | sharded or per-tenant collections for heavy tenants, `maxTimeMS` | shard balance, slow-query log per tenant |
| Q5 | Per-tenant rate limit | absent | gateway-derived tenant header + second limit (Redis) | e2e: two users of one tenant share the tenant quota |
| Q6 | Blast radius | everything shared | cell-based deployment, progressive rollout per cell | deploy to canary cell first; cell health gates |
| Q7 | Isolation evidence | RLS test + e2e | same + pen-test + SOC 2 evidence pack | quarterly evidence refresh |
| Q8 | Tenant migration | not applicable | logical replication with row filters / change streams, write freeze per tenant | migration rehearsal with checksums |
| Q9 | Metering | none | usage events per tenant → warehouse → FinOps | monthly cost-per-tenant report reconciles with bill |

## Prove it

```bash
P() { kubectl -n ticketing exec postgres-0 -- psql -U postgres -d ticketing -tAc "$1"; }
grep -n "statement_timeout" ticket-api/src/main/resources/application.yml   # 5 s statement / 30 s idle-in-tx timeout on every app connection
P "select id, tier, requests_per_minute, max_concurrent from tenant"        # per-tenant quotas enforced by TenantQuotaFilter
P "select * from tenant_usage order by day desc, tenant_id limit 5"          # daily metering per tenant
P "select rolname, rolconnlimit from pg_roles where rolname='ticketing_app'"   # -1 = no per-role connection cap yet (production: per-tier roles)
P "select tenant_id, count(*) from ticket_workflow group by 1 order by 2 desc" # crude per-tenant volume
P "select indexname, indexdef from pg_indexes where tablename='ticket_workflow'"
# per-user limit is enforced, per-tenant is not: two users of the same tenant each get 300/min
for u in alice erin; do T=$(curl -sk https://ticketing.localtest.me:8443/auth/realms/ticketing/protocol/openid-connect/token -d grant_type=password -d client_id=ticketing-ui -d username=$u -d 'password=Passw0rd!' -d scope=openid | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4); \
  echo "$u: $(curl -sk -D - -o /dev/null -H "Authorization: Bearer $T" https://ticketing.localtest.me:8443/api/me | tr -d '\r' | grep -i 'x-ratelimit-remaining-minute')"; done
```

---

## Questions

### Q1. Tenant "acme" runs a month-end close: 40× its normal ticket volume for 3 days. Everyone else slows down. Walk me through detection, immediate mitigation and the permanent design. ★★★★★
**30-second headline:** Detect per tenant, mitigate within the hour (scale stateless tiers, tighten that tenant's quota, statement timeouts), then design for the predictable peak: scheduled capacity, async bulk work, partitioning, and a premium tier.
**Weak answer (what fails):** "Autoscaling handles it" while the shared database is the bottleneck.
**Would I do it again?** Yes; per-tenant quotas and statement timeouts are now in place locally, and I'd add the capacity calendar before the first month-end in production.
**Since implemented:** per-tenant quotas (rate and concurrency), 5 s statement timeout and metering were implemented on 2026-10-05.
**Strong answer:**
*Detection:* per-tenant RED metrics (requests, errors, p95 by tenant label: bounded cardinality),
DB time per tenant (`pg_stat_statements` + `application_name`/comment tagging with tenant), Kong 429
counts per user. Today we would see it only indirectly (latency rises for everyone, `kubectl top`, the
tenant count query above).
*Immediate:* scale the stateless tiers; apply a temporary **per-tenant** quota at Kong (a second
`rate-limiting` instance keyed on a tenant header the gateway derives from the JWT, the same trick as
the `sub` pre-function, but extracting the selected `X-Tenant-ID` only if the token proves it, or
simply on the token's groups); shorten `statement_timeout` for the app role; communicate.
*Permanent:* (1) capacity calendar: month-end is **predictable**, so schedule scale-up (KEDA cron /
HPA min replicas) and pre-warm DB capacity; (2) fair-share admission: per-tenant concurrency limits
in the service (semaphore keyed by tenant), priority for interactive approvals over bulk list/export;
(3) move bulk/reporting work to async jobs on a replica; (4) tier pricing: tenants with predictable
peaks buy reserved capacity or a dedicated cell; (5) data layout: partition `ticket_workflow` by tenant
(LIST) or by time (RANGE, month) so month-end inserts and queries touch hot partitions only.
**Follow-ups / traps:** "Autoscaling solves it": no: the shared database is the bottleneck; scaling
pods adds DB pressure (Scaling Q8).

### Q2. Draw the isolation–cost–performance spectrum for this product and place three tenant tiers on it. ★★★★
**30-second headline:** Three tiers on one codebase: standard (pool cells), business (partitions/schema + quotas), enterprise (silo, own keys/region). Tier is configuration in the tenant registry, routed at the gateway.
**Weak answer (what fails):** One model for everyone, or forking code per tenant.
**Strong answer:**
| Tier | Model | Data isolation | Performance isolation | Cost/tenant |
|---|---|---|---|---|
| Standard (long tail) | pool cell (today's design) | RLS + app filters | quotas per tenant, fair scheduling | lowest |
| Business (heavy/peaky) | pool cell with **dedicated partitions + pool quotas**, or bridge (schema per tenant) | + separate schema/partition | per-tenant connection pool / role limits | medium |
| Enterprise (regulated) | silo (own DB, optionally own cluster/region, own KMS key) | physical | full | highest |
Key design rule: **the code is identical across tiers**; tier = deployment/routing configuration.
Tenant id in the token → gateway routes to the right cell (Kong route by header or upstream per tier);
tenant registry stores tier and cell. Migration between tiers is a supported, tested operation.

### Q3. Sharing one PostgreSQL across tenants: give me every database-level knob for performance isolation. ★★★★★
**30-second headline:** Per-tier roles with connection limits and statement/idle timeouts, separate PgBouncer pools, partitioning, and query attribution; PostgreSQL has no CPU governor, so beyond that you separate instances.
**Weak answer (what fails):** "Add indexes" as the only lever.
**Since implemented:** a 5 s statement timeout and 30 s idle-in-transaction timeout now apply to every application connection.
**Strong answer:** (1) **Per-tenant DB roles** (or role per tier) with `ALTER ROLE … CONNECTION LIMIT`,
`SET statement_timeout`, `SET work_mem`, `SET idle_in_transaction_session_timeout`; the app switches
role per transaction (`SET LOCAL ROLE tenant_x`), which also strengthens RLS (policy can use
`current_user`). (2) **Separate connection pools** per tier in PgBouncer (`pool_size` per database/user)
so a heavy tenant can't take all connections. (3) **Partitioning** by tenant (LIST/HASH) so vacuum,
indexes and hot pages are per tenant; RLS works on partitioned tables. (4) `pg_stat_statements`
attribution by tenant (query comments or `application_name`). (5) Resource governor: PostgreSQL has no
CPU/IO governor, so beyond these you isolate by placing tenants on different instances (cells).
**Applied here:** statement and idle-in-transaction timeouts on every app connection (`application.yml`); no per-role connection limits yet (`rolconnlimit=-1`).

### Q4. Noisy neighbour in MongoDB: what can you do in the shared collection? ★★★★
**30-second headline:** Tenant-leading indexes, query time limits, bounded pages, bucketed history, and for heavy tenants separate collections or zone sharding on {tenantId, _id}.
**Weak answer (what fails):** Only "shard it".
**Since implemented:** queries now carry a 5 s maxTime.
**Strong answer:** Indexes that lead with `tenantId` (have `{tenantId, createdBy}`; list queries need
`{tenantId, createdAt}`); `maxTimeMS` on queries; bounded result sets (page ≤ 200); bucket the
`events` history so heavy tenants don't create huge documents; per-tenant collections or databases for
heavy tenants (bridge) with per-tenant users; sharding on `{tenantId: 1, _id: 1}` (ranged keeps a
tenant together for locality; hashed `_id` component avoids one hot shard for a huge tenant);
zone sharding to pin enterprise tenants to dedicated shards (and regions).

### Q5. Per-user rate limit exists. Why is it insufficient for tenant fairness, and how do you add per-tenant limits safely in Kong OSS? ★★★★
**30-second headline:** Per-user limits don't bound a tenant with many users. Add a tenant quota keyed on the token-proven tenant, never the raw header; implemented in the service (Kong OSS allows one rate-limiting instance per route).
**Weak answer (what fails):** Limiting on the X-Tenant-ID header as sent by the client.
**Since implemented:** implemented as TenantQuotaFilter (requests/minute and concurrency per tenant from the tenant table).
**Strong answer:** A tenant with 500 users gets 500 × 300 requests/min; fairness is about tenants.
Add a second limit keyed on the tenant: the pre-function derives a gateway-owned
`X-Rate-Limit-Tenant` from the token's `groups` **intersected with** the requested `X-Tenant-ID`
(never trust the header alone), cleared otherwise; rate-limiting plugin instance #2 with
`limit_by: header` on it. Kong allows only one instance of a plugin per route, so either a second
route/service for the same path set, a custom plugin, or Redis-backed limits in the service.
Then tier the limit (standard/business/enterprise) via the tenant registry.

### Q6. How do you balance cost and isolation for *failure* isolation (blast radius)? ★★★★
**30-second headline:** Cells: several identical pool deployments each holding a bounded set of tenants, so a bad deploy, poison tenant or DB failure hits one cell; cost stays near pool, blast radius approaches silo.
**Weak answer (what fails):** Confusing cells with replicas.
**Strong answer:** **Cells**: N small, identical pool deployments (each with its own WAF/Kong/service/DB
or at least own DB), each holding a bounded number of tenants. A bad deploy, a poison tenant or a DB
failure affects one cell. Deploy cell by cell (progressive rollout). Cost stays near pool levels
because cells are still shared; isolation approaches silo at scale. Needs: a tenant→cell routing map
(gateway or DNS), tenant migration tooling, cross-cell admin views.

### Q7. A tenant asks for proof that no other tenant can see their data. What evidence do you provide? ★★★★
**30-second headline:** Evidence, not assurances: the layered design, automated cross-tenant tests, the RLS policy dump, code ownership of the tenant path, a pen-test, and an honest residual-risk statement for shared infrastructure.
**Weak answer (what fails):** "Trust us, we filter by tenant_id."
**Strong answer:** Architecture (four isolation layers), automated evidence (RLS integration test,
e2e cross-tenant tests: other tenant's ticket → 404, header for unproven tenant → 403), DB policy
dump (`pg_policy`), code ownership/review of `TenantResolver`/`DocumentStore`, pen-test report,
and the residual risk statement for the pool model (shared hardware, shared MongoDB collection without
DB-enforced isolation). Offer the higher tier if the residual risk is unacceptable.

### Q8. Moving a tenant from the pool to a silo: design the migration with no data loss and minimal downtime. ★★★★★
**30-second headline:** Provision silo, copy by tenant (logical replication with row filters / change streams), reconcile, short per-tenant write freeze, switch routing, keep the old copy read-only for rollback, then erase.
**Weak answer (what fails):** A big-bang export/import with downtime for all tenants.
**Strong answer:** Provision silo; copy tenant data (Postgres rows filtered by `tenant_id` via
logical replication with row filters (PG15+), or dump/restore by tenant; Mongo `tenantId` export +
change stream for catch-up); verify with reconciliation counts and checksums; short write freeze for
that tenant only (tenant `active=false` blocks creation; a maintenance flag at Kong blocks writes);
switch routing in the tenant registry; unfreeze; keep the pool copy read-only for rollback, then
delete (retention/erasure). Keycloak identities stay global (same realm), only data moves.

### Q9. How would you meter and bill tenants fairly? ★★★★
**30-second headline:** Meter per tenant (requests, throttles, tickets, storage, DB time), aggregate daily, reconcile with infrastructure cost to get unit cost per tenant, and keep per-tenant detail out of high-cardinality metrics.
**Weak answer (what fails):** "Divide the bill by tenant count."
**Since implemented:** daily per-tenant metering is implemented (tenant_usage table + Prometheus counters).
**Strong answer:** Emit usage events per request (tenant, user, endpoint, status, duration) from Kong
(log plugin) or the service; aggregate per tenant per day (requests, storage from DB sizes per tenant,
DB time from `pg_stat_statements` tagging); store in a usage table/warehouse; reconcile against
infrastructure cost (FinOps allocation) to compute unit cost; drive tiers and quotas from it. Beware
cardinality in metrics; use logs/events for per-tenant detail.

### Q10. Rapid fire
* What stops tenant A's query reading tenant B's rows if the code forgets a filter? → forced RLS (and Hibernate `@TenantId`).
* What stops tenant A's query running for an hour? → the 5 s statement timeout on application connections (per-tier roles next).
* Is the rate limit per tenant? → both: per user at Kong (`sub`), per tenant in the service (`TenantQuotaFilter`).
* Best lever for a predictable month-end peak? → scheduled capacity + async bulk work + per-tenant quotas.
* Where would tenant tier/cell routing live? → tenant registry (`tenant` table) + gateway routing.
