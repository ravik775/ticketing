# Architecture: Solution & Enterprise Architect questions

## Concepts you must own

* **Architecture characteristics** ("-ilities"): security, availability, scalability, performance,
  maintainability, observability, operability, cost. Architecture is choosing which ones win when they
  conflict, and writing that down.
* **Multi-tenancy models:** *silo* (dedicated stack/DB per tenant), *bridge* (shared app, DB/schema per
  tenant), *pool* (shared everything, tenant column + row filtering). Trade isolation and noisy-neighbour
  control against cost and operational simplicity.
* **Defence in depth / Zero Trust:** independent controls at every hop; identity over network location.
* **Polyglot persistence:** choose the store per data shape and consistency need; pay for it with
  cross-store consistency work.
* **Architecture Decision Records (ADRs):** context → decision → consequences, immutable, numbered.
* **C4 model:** Context, Containers, Components, Code: the levels at which you explain a system.
* **Enterprise view (TOGAF ADM):** Business → Data → Application → Technology architectures; gap
  analysis; transition architectures; governance via an architecture board and compliance reviews.

## How this application applies them

| Concept | Decision in this system | Where |
|---|---|---|
| Multi-tenancy | **Pool model**: shared services and databases; tenant = JWT group; isolation by Hibernate `@TenantId`, PostgreSQL **forced RLS**, and a single Mongo access class | `ticket-core/…/internal/*`, `V1__tickets.sql` |
| Identity-centric security | Tenant & email only from the validated token; header only *selects* a proven tenant | `TenantResolver.java` |
| Layered edge | WAF → Kong → service, each with its own job (attacks / authN + quotas / authZ + business rules) | `k8s/80-waf.yaml`, `scripts/render-kong.sh` |
| Polyglot persistence | PostgreSQL for workflow/locks (ACID, constraints, RLS); MongoDB for documents + history | `TicketService.java` |
| Modularity as a security boundary | JPMS: only the `TicketService` facade is exported | `module-info.java` |
| Separation of duties | domain rule + DB `CHECK` | `TicketWorkflow.java`, `V3__separation_of_duties.sql` |
| Evolution | Flyway migrations, versioned manifests, scripted environment | `db/migration`, `scripts/up.sh` |

## Local (narrowed) → Production (unwrapped)

> The local k3d deployment is the reference implementation and stays unchanged. Production changes
> named in answers are **proposals that need architecture approval**.

| Q | Aspect | Local (narrowed) | Production (unwrapped) | Validate in production |
|---|---|---|---|---|
| Q1 | Containers & entry | one node, one replica each; WAF on laptop port 8443 | multi-AZ EKS, ALB + AWS WAF in front of the in-cluster WAF, ≥ 2 replicas | C4 diagram matches `kubectl get deploy -A`; synthetic journey per AZ |
| Q2 | Tenancy model | pool only, two demo tenants | tiered: pool cells for standard, bridge/silo for regulated or heavy tenants | per-tenant isolation tests in each cell; tenant→cell registry audit |
| Q3 | Two stores | in-cluster Postgres + Mongo (1-member replica set), outbox between them | RDS + Atlas/DocumentDB (or one store with JSONB, by ADR) | reconciliation job + outbox lag metric |
| Q4 | Top risks | documented gaps (`docs/08` §8.8) | risk register with owners, expiry, funding | quarterly risk review evidence |
| Q5 | Double JWT validation | same | same (non-negotiable); add audience mapper | ID-token canary returns 401 |
| Q6 | PDP/PEP | embedded in code | consider external PDP for cross-service policy; keep domain invariants in code | policy unit tests + decision logs |
| Q7 | Residency/SLA | not applicable | region-pinned cells, Multi-AZ, SLO 99.95 % | SLO dashboards, DR drill reports |
| Q8 | ADRs | rationale spread across docs | numbered ADRs in repo, ARB review | ADR index current at each release |
| Q9 | Events | synchronous only | outbox → broker for side effects | consumer lag + idempotency tests |
| Q10 | Fitness functions | `e2e.sh` on demand, JPMS, RLS test | same suites in CI/CD + scheduled canaries + policy-as-code | failing canary blocks deploy / pages |
| Q11 | Year-one changes | n/a | secrets manager, cert reload automation, observability stack | gap list shrinking, no open accepted gaps in prod |
| Q12 | Failure modes | single points everywhere | redundancy per tier; runbooks; graceful degradation | game days per failure mode |
| Q13 | EA landscape | bundles its own IdP/WAF/PKI | consumes enterprise platforms (shared IdP, edge, PKI) | architecture compliance review |
| Q14 | Gateway compromise | same blast-radius reasoning | add sender-constrained tokens, anomaly detection | red-team exercise |

## Prove it

```bash
bash scripts/e2e.sh                                   # 53 checks across every layer
kubectl get pods -A -o wide                           # the deployed containers (C4 level 2)
kubectl -n ticketing exec postgres-0 -- psql -U postgres -d ticketing -c '\d+ ticket_workflow'   # RLS + constraints
grep -n "exports\|opens" ticket-core/src/main/java/module-info.java                              # the module boundary
```

---

## Questions

### Q1. Walk me through this system at C4 levels 1 and 2 in under three minutes. ★★★
**30-second headline:** Single public entry (WAF) → Kong → mTLS → Spring service → PostgreSQL (state, RLS) + MongoDB (details); Keycloak for identity. Drivers: tenant isolation and Zero Trust. Main trade-off: two stores, so consistency is handled by an outbox.
**Weak answer (what fails):** Lists boxes without protocols, trust mechanisms or what each store owns; never names the trade-off.
**Strong answer:** L1: users (applicants, approvers, admins), external Google IdP, the ticketing
system. L2: WAF (nginx/ModSecurity), Kong gateway, Keycloak, UI (static SPA), ticket-service (Spring
Boot), PostgreSQL, MongoDB, cert-manager. Name the protocol and trust mechanism on every arrow
(TLS, verified TLS, mTLS, JWT), the data owned by each store, and the single public entry point.
Finish with the top two quality attributes driving the design (tenant isolation, Zero Trust) and the
top trade-off (two stores ⇒ no cross-store transaction).
**Applied here:** `docs/04-architecture.md`.
**Follow-ups / traps:** "Which box is stateful?" (PostgreSQL, MongoDB, Keycloak via its DB; Kong is
DB-less and stateless except per-pod rate-limit counters). Candidates who forget the counters fail the
scaling follow-up.

### Q2. Justify the *pool* tenancy model. When would you switch to silo or bridge? ★★★★
**30-second headline:** Pool tenancy is the cost-efficient default here because isolation is enforced in four independent layers and tested; move a tenant to bridge/silo for residency, contractual isolation or dominant load, with identical code and routing by tier.
**Weak answer (what fails):** "Pool is cheaper" with no mention of how isolation is enforced or of noisy neighbours.
**Would I do it again?** Yes, for the long tail. I would design the tier/cell routing (tenant registry → gateway) on day one so moving a tenant is configuration, not a project.
**Strong answer:** Pool minimises cost and operational overhead and lets one deployment serve many
tenants; the risk is a single isolation bug leaking data across tenants and noisy neighbours. It is
acceptable here because isolation is enforced in **three independent layers** (application-derived
tenant, Hibernate filter, forced RLS that fails closed) and verified by tests. Move to **bridge**
(schema/DB per tenant) for regulatory data residency, per-tenant backup/restore, or large tenants
whose load dominates; **silo** for contractual isolation or custom versions. A hybrid (pool for the
long tail, silo for premium) is common; keep tenant routing in the identity/gateway layer so the code
does not fork.
**Applied here:** tenant registry table + RLS policy; tenant id derived from token groups.
**Prove it:** the RLS fail-closed query in `docs/03-database-investigation.md` §3.1 (returns 0 rows
without a tenant).
**Follow-ups / traps:** "How do you restore one tenant's data in the pool model?" (PITR into a side
database, then copy that tenant's rows; plan it before you need it). "How do you delete one tenant
for GDPR?" (both stores, plus backups retention policy.)

### Q3. Why two databases? Defend it against a CTO who wants "just Postgres with JSONB". ★★★★★
**30-second headline:** Honestly, PostgreSQL + JSONB would work and avoids dual writes. Two stores is a deliberate trade-off (relational invariants vs document history) that I made safe with a transactional outbox; I'd write the ADR and revisit it.
**Weak answer (what fails):** "Mongo is faster for documents" or claiming the two writes are transactional.
**Would I do it again?** Probably not at this scale: I'd start with Postgres JSONB and add a document store only when shape/volume demands it. The outbox now makes the current design safe, but it is complexity I paid for.
**Strong answer:** The honest answer is that Postgres + JSONB *would* work and removes the dual-write
problem; the choice here is a trade-off, not a necessity. Arguments for the split: workflow state
needs atomic transitions, constraints, optimistic locking and RLS (relational strengths); ticket
content is a growing document with embedded history, read as a whole. Arguments against: no single
transaction (originally mitigated with Mongo-first insert + compensation; now with a transactional outbox),
two backup/restore regimes, two skill sets. A strong candidate says: *"For this scale I would accept
JSONB and one store; I'd keep Mongo only if document volume/shape or team skills justify it, and I'd
write an ADR either way."* Mention outbox/CDC as the next step if the split stays.
**Applied here:** `WorkflowStore` + `OutboxStore` (event in the same transaction), `OutboxPublisher` (projection + retries).
**Prove it:** `docs/03` §3.4 consistency check (per-tenant counts identical in both stores).
**Follow-ups / traps:** "What exactly happens if Mongo is down when an approver approves?" (Postgres
commits, history entry lost, WARN logged → status and history diverge). Saying "it's transactional"
is an instant fail.

### Q4. What are the system's top three architectural risks, and what would you do in the next quarter? ★★★★
**30-second headline:** Three risks with owners and exit criteria: cross-store consistency, certificate operations, and single-replica availability; prioritised by likelihood × impact. Two are now closed locally (outbox, PITR + restore drill); production HA remains.
**Weak answer (what fails):** A generic list ("security, performance") without owners, measures or priority.
**Since implemented:** the outbox, backups/PITR and alerting from this answer were approved and implemented on 2026-10-05.
**Strong answer:** (1) **Cross-store consistency**: introduce a transactional outbox in Postgres
and an idempotent projector to Mongo. (2) **Certificate operations**: renewals need manual reload;
automate (Spring SSL reload, Kong reading certs from secrets, alerting on expiry). (3) **Single
replicas / no backups**: managed databases, 2+ replicas, PDBs, restore drills. Each with owner,
measurable exit criterion and cost.
**Applied here:** `docs/08-security-zero-trust.md` §8.8 gap list.
**Follow-ups / traps:** Prioritisation reasoning: risk = likelihood × impact; data loss beats
inconvenience.

### Q5. Draw the trust boundaries and explain why the service re-validates a JWT that Kong already validated. ★★★★
**30-second headline:** Zero Trust: the gateway is an optimisation, not a trust anchor. Kong checks signature and expiry; the service re-checks issuer, token type, authorised party, tenant and role, so a bypass or misconfigured gateway grants nothing.
**Weak answer (what fails):** "Double validation is redundant/slow."
**Would I do it again?** Yes, without hesitation: it costs microseconds and removed a whole class of gateway-bypass risks.
**Strong answer:** Zero Trust: Kong is not a reason to trust; a compromised or misconfigured gateway,
a bypass path, or a future second gateway must not grant access. Kong checks only signature and
expiry; the service also checks issuer, token type (`typ=Bearer`, rejecting ID tokens), authorised
party (`azp=ticketing-ui`), tenant membership and roles. Kong is optimisation (reject early, shed
load) plus quotas; the service is the policy enforcement point for business authorisation.
**Applied here:** `JwtDecoderConfig.java`, `render-kong.sh` (`claims_to_verify: exp`).
**Prove it:** e2e "an ID token is not accepted as an access token" passes Kong yet returns 401 from the service.
**Follow-ups / traps:** "Isn't double validation a performance problem?" (JWKS cached, RS256 verify is
microseconds; negligible versus a DB round trip.)

### Q6. Where is the policy decision point vs policy enforcement point in this design? Would you externalise authorisation (OPA, Cedar, Keycloak Authorization Services)? ★★★★★
**30-second headline:** PDP and PEP are co-located in code today (fast, simple). Externalise to OPA/Cedar when many services share policy or policy changes faster than code; keep business invariants like no-self-approval in the domain and tenant isolation in the database.
**Weak answer (what fails):** "Put everything in OPA" without latency, availability and testing costs.
**Strong answer:** Today PDP and PEP are co-located in code: `@PreAuthorize` + `TicketService`
checks + domain rules + RLS. That is simple and fast but couples policy to deploys. Externalise when
policies change faster than code, multiple services need the same rules, or auditors need policy as
data. Cost: latency, availability coupling to the PDP, policy testing discipline. Fine-grained
*business* invariants (no self-approval, lock holder decides) should stay in the domain model even
then: they are not "authorisation policy", they are correctness.
**Follow-ups / traps:** "Which rule would you never externalise?" (tenant isolation at the DB:
defence in depth must not depend on a network call.)

### Q7. A large customer demands their data in the EU and a 99.95% SLA. Redesign. ★★★★★
**30-second headline:** EU residency and 99.95 % mean a region-pinned cell for that tenant, multi-AZ everything, managed HA databases, ≥ 2 replicas with PDBs, and a 21.9-minute monthly error budget backed by change management and SLO monitoring.
**Weak answer (what fails):** "Add multi-AZ" as if infrastructure alone delivers 99.95 %.
**Strong answer:** Identify drivers (residency, availability). Options: region-pinned *bridge/silo*
deployment for that tenant (separate DBs in eu-region; routing by tenant at DNS/gateway); multi-AZ
everything; managed DBs with Multi-AZ; 2+ replicas with PDBs and anti-affinity; error budget = 21.9
min/month. Keycloak: realm per region or global realm with regional token issuers (issuer URL changes
⇒ Kong JWT key and service issuer configuration per region). Data residency also covers logs,
backups and support access. Cost and operating model change: write it as a transition architecture.
**Follow-ups / traps:** "Does multi-AZ get you 99.95% alone?" (No: deployments, certificate expiry
and human error dominate; you need change management, automation and SLO monitoring.)

### Q8. What would you put in the first five ADRs for this system? ★★★
**30-second headline:** Five ADRs: pool tenancy with RLS; two stores plus outbox; Kong OSS JWT plugin instead of OIDC; mTLS with caller pinning; local WAF plus cloud WAF later. Each with context, options, decision, consequences and review date.
**Weak answer (what fails):** Listing technologies instead of decisions with consequences.
**Strong answer:** (1) Pool multi-tenancy with RLS. (2) Two stores (or one: Q3) with the
consistency approach. (3) Kong OSS DB-less with JWT plugin instead of an OIDC plugin (Enterprise).
(4) mTLS between gateway and service with caller pinning. (5) Local WAF (ModSecurity/CRS) vs cloud WAF.
Each: context, options considered, decision, consequences, review date.
**Follow-ups / traps:** "Who approves an ADR and when is it superseded?" (architecture review/board,
new ADR referencing the old one; never edit history.)

### Q9. Event-driven or request/response? Where would events help this system? ★★★★
**30-second headline:** Keep commands synchronous (locks need immediate consistency); use events for side effects such as notifications, audit streams and projections, fed by the outbox with at-least-once delivery and idempotent consumers.
**Weak answer (what fails):** "Make it all event-driven" without addressing the locking semantics.
**Strong answer:** Core flows are user-driven and need immediate consistency for locking: keep
synchronous request/response. Events help for side effects: notify applicant on decision, analytics,
audit stream, search indexing, syncing the Mongo projection (outbox → broker). Use events where
eventual consistency is acceptable and fan-out is valuable; keep the lock decision synchronous and
in Postgres.
**Follow-ups / traps:** "Exactly-once?" (no: at-least-once + idempotent consumers keyed by ticket id +
version.)

### Q10. How do you know the architecture is still what the diagrams say? ★★★★
**30-second headline:** Fitness functions: JPMS compile boundary, the RLS integration test, e2e checks that NetworkPolicies and the WAF still block, CI gates and scheduled canaries. Diagrams drift; executable checks don't.
**Weak answer (what fails):** "We review the diagrams quarterly."
**Strong answer:** Fitness functions: automated checks that fail the build or deployment when an
architectural rule is broken. Here: JPMS compile-time boundary, e2e tests that assert NetworkPolicy
blocks, RLS integration test, WAF block tests. Add: ArchUnit for package rules, policy-as-code
(Kyverno/OPA Gatekeeper) for pod security and image digests, drift detection (GitOps).
**Applied here:** `TicketFlowIntegrationTest.rowLevelSecurityFailsClosedAndIsolatesTenants`, e2e
"Zero Trust / network" section.
**Prove it:** `bash scripts/e2e.sh | grep -E "NetworkPolicy|mTLS"`.

### Q11. You inherit this system. Which three things would you refuse to change in year one, and which three would you change immediately? ★★★★
**30-second headline:** Keep the invariants (token-derived tenant, forced RLS, mTLS + caller pinning); change the operational gaps first (secrets, certificate reload, observability). Shows judgement about what is load-bearing.
**Weak answer (what fails):** Rewrite proposals ("move to microservices/event sourcing").
**Since implemented:** secrets management and observability are now implemented locally.
**Strong answer:** Keep: tenant derivation from token only; forced RLS; mTLS + caller pinning. These
are cheap, high-value invariants. Change: secrets out of the repo (accepted gap ends at first shared
environment); certificate reload automation; observability (metrics, traces, SLOs). Shows judgement
about invariants vs debt.

### Q12. How does this architecture fail? Give me three failure modes and the user-visible symptom of each. ★★★★
**30-second headline:** Keycloak down: no new logins, existing tokens work ≤ 5 min. MongoDB down: changes succeed, history arrives later via the outbox. Expired internal certificate: 502 on all API calls. Each has a detection and a recovery path.
**Weak answer (what fails):** Only "the service goes down".
**Since implemented:** MongoDB-down behaviour changed with the outbox (history is delayed, no longer lost).
**Strong answer:** (a) Keycloak down → new logins fail; existing tokens keep working up to 5 min;
service still validates (JWKS cached). (b) Mongo down → creates fail (compensated), decisions succeed
in Postgres but history is lost (WARN), reads show "(details unavailable)". (c) Expired internal cert
→ Kong 502 on all `/api` calls while UI and login still work. Pair each with detection (log/alert)
and recovery.
**Prove it:** `kubectl -n ticketing scale statefulset mongo --replicas=0` (in a lab), then call the
API; restore with `--replicas=1`.

### Q13. Enterprise view: where does this system sit in a TOGAF landscape and what governance would apply? ★★★★
**30-second headline:** Case-management application consuming enterprise building blocks (identity, gateway, WAF, PKI, Kubernetes) with PII-driven data architecture; governed by an architecture contract, compliance reviews and time-boxed dispensations.
**Weak answer (what fails):** Naming TOGAF phases without mapping them to this system.
**Strong answer:** Application architecture: a case-management capability. Data architecture: tenant,
ticket and audit entities with classification (PII: email, mobile) driving retention, encryption and
residency. Technology architecture: the platform services it consumes (identity, gateway, WAF, PKI,
Kubernetes), ideally shared enterprise building blocks rather than per-app copies. Governance:
compliance review against principles (identity-centric security, API-first, data classification),
dispensations for accepted gaps with expiry dates, and an architecture contract with the delivery team.
**Follow-ups / traps:** "Would each application run its own Keycloak and WAF?" (No: enterprise IdP and
edge are shared platforms; this repo bundles them for a self-contained demo.)

### Q14. What is the blast radius if the Kong pod is fully compromised? ★★★★★
**30-second headline:** An attacker owning Kong can call the API as kong-gateway but still needs a valid user token for every call and cannot reach databases, the Kubernetes API or Kong's own config; short tokens limit replay.
**Weak answer (what fails):** "Game over, the gateway is trusted."
**Would I do it again?** Yes; next I'd add sender-constrained tokens (DPoP/mTLS-bound) to close the token-replay window.
**Strong answer:** The attacker holds Kong's client certificate (can call ticket-service as
`kong-gateway`) but **still needs a valid user JWT** for every call, and the service enforces tenant
and role from that token, so they can only act as users whose tokens they intercept (they *can*
read bearer tokens in transit; tokens live 5 minutes). They cannot reach databases (NetworkPolicy),
cannot talk to the Kubernetes API (no service-account token), cannot modify Kong config (read-only
secret, admin API off). Mitigations: short token lifetimes, sender-constrained tokens (DPoP/mTLS-bound),
anomaly detection.
**Follow-ups / traps:** Candidates who say "the gateway is trusted, so game over" miss the point of the design.

### Q15. Rapid fire
* Single public entry point? → WAF Service `edge/waf` (LoadBalancer); Kong is ClusterIP.
* Where does tenant come from? → token `groups`; `X-Tenant-ID` only selects among proven tenants.
* Why 404 instead of 403 for another user's ticket? → avoid existence disclosure.
* Why DB-less Kong? → config as code, no DB to secure/operate, immutable deploys; cost: restart to change config.
* What proves isolation at the DB? → RLS integration test + `docs/03` §3.1 query.
