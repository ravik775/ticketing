# Back-of-envelope exercises (with worked answers)

Senior panels hand you a whiteboard and a number. What they assess: **state assumptions, do the
arithmetic out loud, sanity-check, then say what you'd measure to replace the assumption.** Round
aggressively; order of magnitude matters more than the third digit.

Useful constants: 1 day ≈ 86,400 s (≈ 10⁵); 1 month ≈ 2.6 M s ≈ 720 h; Little's Law **L = λ × W**
(in-flight = arrival rate × time in system).

The local measured baseline (k6, 20 users, one replica each): **36.6 req/s, p50 36 ms, p95 109 ms**,
ticket-service ~0.48 cores, PostgreSQL ~0.17 cores, MongoDB ~0.16 cores (see `12-Scaling.md`).

---

### E1. Database connections: "We'll run 12 service replicas with HikariCP 10 each. Is PostgreSQL OK?" ★★★★
**30-second headline:** 120 app connections plus Keycloak's pool plus admin/migrations exceeds a default `max_connections` of 100; but Little's Law says the database only needs ~10–20 concurrent connections, so shrink pools and put PgBouncer in front.
**Weak answer (what fails):** "Increase max_connections to 1000."
**Worked answer:**
* Demand: 12 × 10 = **120** + Keycloak (Quarkus pool default max 100, size it to ~20) + migrations/admin ≈ **150**.
* Need: assume peak 1,000 req/s and ~2 queries per request at ~4 ms each → DB time per request ≈ 8 ms.
  L = 1,000 × 0.008 = **8 connections busy on average**; ×2 for bursts → ~16–20.
* So pools are ~7× oversized: idle connections cost ~5–10 MB each on the server and context switches.
* Decision: Hikari max 4–5 per replica, or PgBouncer (transaction pooling, `prepareThreshold=0`) with
  ~20 server connections; alert on `pg_stat_activity` > 80 % of `max_connections`.
* Measure next: actual DB time per request from traces (Tempo spans) and Hikari `pending` metrics.

### E2. Storage growth: 10,000 tenants, 500 tickets per tenant per month, 3-year retention. ★★★★
**30-second headline:** About 5 M tickets/month: ~3 GB/month in PostgreSQL, ~18 GB/month in MongoDB, a rolling ~10 GB outbox; roughly 0.8 TB of primary data after three years, before replicas and backups.
**Weak answer (what fails):** Forgetting indexes, the outbox, WAL and backups, or answering without per-row sizes.
**Worked answer (assumptions stated):**
* Tickets: 10,000 × 500 = **5 M/month**, 60 M/year, 180 M in 3 years.
* PostgreSQL `ticket_workflow`: ~200 B row + ~2 indexes ≈ **~600 B/ticket** → 5 M × 600 B ≈ **3 GB/month**, ~108 GB in 3 years.
* MongoDB `ticket_details`: ~1 KB content + ~6 events × ~150 B ≈ 2 KB, +20 % index/overhead ≈ **~2.5–3 KB**
  → 5 M × 3 KB ≈ **15–18 GB/month**, ~0.6 TB in 3 years (the unbounded `events` array is the risk).
* Outbox: ~6 events/ticket × 5 M = 30 M rows/month × ~350 B ≈ 10 GB, but **retention 30 days** keeps it
  at ~10 GB rolling (purge job).
* Total primary data ≈ 0.7–0.8 TB in 3 years; ×3 for Multi-AZ/replicas; backups with 14-day PITR add WAL
  volume (≈ 2–3× daily write volume).
* Decisions: partition `ticket_workflow` by month; bucket Mongo history; archive closed tickets after N
  months to cheap storage; per-tenant metering already shows who drives growth.

### E3. Token refresh load: 100,000 users active at the same time. What does Keycloak see? ★★★★
**30-second headline:** With 300-second access tokens refreshed ~30 s early, each active user refreshes every ~270 s, so 100 k users ≈ 370 refreshes/s continuously, plus logins; doubling token lifetime halves the load but doubles the revocation window.
**Weak answer (what fails):** Counting only logins, or "tokens are validated locally, so Keycloak is idle".
**Worked answer:**
* Refresh rate = 100,000 / 270 s ≈ **370 req/s** (steady state).
* Logins: assume 20 % of users log in each hour → 20,000/3,600 ≈ 6/s, but **password hashing is
  deliberately expensive**: locally, 40 logins in a burst pushed Keycloak to ~1.8 cores. Logins, not
  refreshes, drive CPU spikes (morning peaks).
* CPU estimate (assumption to measure): ~5 ms CPU per refresh → 370 × 5 ms ≈ **1.9 cores**; plan 2–3
  Keycloak replicas with headroom, scale on CPU, warm before 9 a.m.
* Levers: longer access tokens (fewer refreshes, longer revocation window: a security trade-off); SSO
  sessions to avoid repeated password logins; federate to customer IdPs (moves hashing off your cluster).
* API validation stays local (JWKS cached), so the API tier does **not** scale with Keycloak.

### E4. Cost per tenant on AWS. ★★★★★
**30-second headline:** The baseline platform is a few thousand dollars a month of mostly fixed cost; at 50 tenants that is tens of dollars per tenant, at 1,000 tenants a few dollars. Fixed cost dominates, which is why the pool model and one database store matter.
**Weak answer (what fails):** Pricing only EC2, or quoting a number without assumptions.
**Worked answer (illustrative list prices, verify current pricing for your region):**

| Item | Assumption | ~USD / month |
|---|---|---|
| EKS control plane | 1 cluster | 75 |
| Worker nodes | 3 × general-purpose (2 vCPU, 8 GB), Graviton | 180 |
| RDS PostgreSQL | Multi-AZ, 2 vCPU / 16 GB, 100 GB | 400 |
| Document store | DocumentDB 2 instances, or Atlas M30 | 400–600 |
| ElastiCache (rate limits) | small instance | 25 |
| ALB + AWS WAF | base + rules + requests | 60 |
| NAT gateway | 1–3 AZ + data | 35–110 |
| Logs/metrics/traces | managed, modest volume | 150 |
| Secrets Manager / KMS | ~20 secrets, few keys | 15 |
| **Total** | | **≈ 1,350–1,600** |

* Per tenant: 50 tenants → **~$30/tenant/month**; 1,000 tenants → **~$1.5** (variable costs then start to
  matter: storage, requests, logs).
* Biggest lever: one store (PostgreSQL + JSONB) removes the document database (~30 % of the bill).
  Next: right-size from metrics, Savings Plans, VPC endpoints to cut NAT data, log sampling.
* Use the metering already built (`tenant_usage`) to allocate variable cost to tenants and to price tiers.

### E5. Error budget: "We promise 99.9 %. How many bad deploys can we afford?" ★★★★
**30-second headline:** 99.9 % over 30 days is 43 minutes. A 30-minute outage from an expired certificate burns ~70 % of it; a 14.4× burn rate empties 2 % of the monthly budget per hour, which is why it pages.
**Weak answer (what fails):** Not knowing the minutes, or treating SLO and SLA as the same.
**Worked answer:**
* Budget = (1 − 0.999) × 30 × 24 × 60 = **43.2 min/month**; 99.95 % → 21.6 min; 99.5 % (local SLO) → 3.6 h.
* Serial dependencies multiply: five components at 99.9 % each → 0.999⁵ ≈ **99.5 %**: you cannot promise
  99.9 % end to end on single instances.
* A deploy causing 5 % errors for 4 minutes costs 4 × 0.05 = 0.2 "full-outage minutes": you can afford
  ~200 of those, but **one** 30-minute certificate expiry costs 30 min = **69 %** of the budget. Spend
  engineering on the outage classes, not on perfect deploys.
* Burn-rate maths behind the alerts in Prometheus: 14.4× for 1 hour consumes 14.4 / 720 = **2 %** of a
  30-day budget (page); 6× for 6 hours consumes 5 % (ticket).
* Policy: when the budget is spent, freeze risky changes and prioritise reliability work.

### E6. Bonus: Little's Law on the measured baseline. ★★★
**30-second headline:** 36.6 req/s × ~0.046 s mean latency ≈ 1.7 requests in flight, so one service replica with a 10-connection pool was nowhere near saturation; the next test must step the load until latency bends.
**Weak answer (what fails):** Extrapolating capacity linearly from one data point.
**Worked answer:**
* Mean latency ≈ 46 ms (p50 36 ms, long tail to p99 176 ms). L = 36.6 × 0.046 ≈ **1.7 in flight**.
* CPU per request at the service: 0.48 cores / 36.6 req/s ≈ **13 ms CPU/request** → one core ≈ 75 req/s,
  so a 2-core replica tops out near ~150 req/s *if* nothing else saturates first (an estimate, not a fact).
* Keycloak, not the API, was the hottest component (1.78 cores) because the test logged users in:
  separate login load from API load when you size.
* Next measurement: step load (20 → 50 → 100 → 200 VUs) and record the knee where p95 crosses the
  500 ms SLO bucket; that number, divided by headroom (~60 %), sets the HPA target.

---

## How to practise
Say the assumptions first, write the formula, round, then give a decision and the metric that would
replace your assumption. If the interviewer changes a number, redo only the line it affects.
