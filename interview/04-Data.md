# Data architecture: PostgreSQL (RLS), MongoDB, consistency across two stores

## Concepts you must own

* **Row-Level Security (PostgreSQL):** `ENABLE` turns policies on for non-owners; `FORCE` applies them
  to the table owner too; superusers and roles with `BYPASSRLS` always bypass. `USING` filters
  visible rows (SELECT/UPDATE/DELETE); `WITH CHECK` validates new/updated rows (INSERT/UPDATE).
  `current_setting(name, true)` returns NULL when unset → predicate NULL → no rows (**fail closed**).
* **Constraints as executable business rules:** `CHECK`, `FOREIGN KEY`, `UNIQUE`; `NOT VALID` +
  `VALIDATE CONSTRAINT` for low-lock additions.
* **Isolation levels & optimistic locking:** READ COMMITTED default; version columns detect lost updates.
* **Document modelling (MongoDB):** embed vs reference; 16 MB document limit; unbounded arrays are an
  anti-pattern; indexes must match query shapes; no RLS → isolation in the application layer or via
  separate databases/collections per tenant.
* **Dual-write problem:** two stores cannot commit atomically without 2PC; patterns: compensation,
  transactional **outbox** + relay, CDC (Debezium), idempotent consumers, reconciliation jobs.
* **Multi-tenant data operations:** per-tenant restore, erasure (GDPR Art. 17), residency, hot tenants,
  partitioning/sharding by tenant.

## How this application applies them

| Concept | Implementation | Where |
|---|---|---|
| Forced, fail-closed RLS | `ENABLE` + `FORCE ROW LEVEL SECURITY`; policy `tenant_id = current_setting('app.tenant_id', true)` for `USING` and `WITH CHECK` | `V1__tickets.sql` |
| Transaction-local tenant | `set_config(..., true)` per transaction | `WorkflowStore.bindTenant()` |
| Constraints | status enum `CHECK`; `LOCKED ⇔ locked_by IS NOT NULL`; owner ≠ lock holder; FK to `tenant` | `V1`, `V3` |
| Indexes | `(tenant_id, owner_email, created_at DESC)`, `(tenant_id, status, created_at DESC)` | `V1` |
| Tenant registry | `tenant(id, name, active)`; inserts refused for unknown/inactive tenants | `V2`, `WorkflowStore.insert` |
| Documents | `ticket_details` `{_id: uuid, tenantId, title, mobile, description, createdBy, createdAt, events[]}`; index `{tenantId, createdBy}` | `TicketDocument`, `21-mongo.yaml` |
| Mongo isolation | only `DocumentStore` touches the collection; every query adds `tenantId` from the security context; insert rejects foreign tenant | `DocumentStore.java` |
| Dual write | transactional outbox: event committed with the state change; projected immediately, retried by the relay; idempotent by event id | `WorkflowStore`, `OutboxStore`, `OutboxPublisher`, `DocumentStore` |
| Least privilege | Mongo `ticketing_app` readWrite on `ticketing` only; Postgres separate users per database | `21-mongo.yaml`, `20-postgres.yaml` |

## Local (narrowed) → Production (unwrapped)

> Local design unchanged; production items are **proposals requiring approval**.

| Q | Aspect | Local (narrowed) | Production (unwrapped) | Validate in production |
|---|---|---|---|---|
| Q1 | RLS ownership | app user owns tables → `FORCE` needed | migration owner role ≠ app role; app role without `BYPASSRLS` | `pg_tables.tableowner` ≠ app user; RLS test as app role |
| Q2 | Tenant binding | same | same + per-tenant role option (`SET LOCAL ROLE`) | integration test per release |
| Q3 | Pooling | HikariCP only | PgBouncer transaction pooling (works: setting is transaction-local) | test against PgBouncer in staging |
| Q4–Q5 | Dual write | transactional outbox + relay + alerts (implemented) | transactional outbox + idempotent projector | outbox lag/backlog metrics, reconciliation alert |
| Q6 | History growth | embedded unbounded array | bucketed events collection, summary projections | document size percentiles |
| Q7 | Mongo isolation | single collection, app filter | per-tenant DB/collection for regulated tenants, CSFLE | access tests per tenant user |
| Q8 | Erasure | manual queries | scripted, audited erasure across stores, Keycloak, logs; backup expiry/crypto-shredding | erasure certificate per request |
| Q9 | Hot tenant | indexes lead with tenant | partitioning, read replicas, sharding, silo | per-tenant DB time |
| Q10 | Backups | none (laptop) | PITR (RDS), continuous backups (Atlas), cross-region copies | quarterly restore drill with RPO/RTO measured |

## Prove it

```bash
P() { kubectl -n ticketing exec postgres-0 -- psql -U postgres -d ticketing -c "$1"; }
P "select relname, relrowsecurity, relforcerowsecurity from pg_class where relname='ticket_workflow';"   # t | t
P "select polname, pg_get_expr(polqual, polrelid) as using, pg_get_expr(polwithcheck, polrelid) as check from pg_policy;"
P "select conname, pg_get_constraintdef(oid) from pg_constraint where conrelid='ticket_workflow'::regclass;"
P "select rolname, rolsuper, rolbypassrls from pg_roles where rolname in ('postgres','ticketing_app','keycloak');"
# fail-closed as the application user (0 rows without a tenant) - see docs/03 §3.1
kubectl -n ticketing exec mongo-0 -- sh -c 'mongosh --quiet -u ticketing_app -p "$MONGO_APP_PASSWORD" --authenticationDatabase ticketing ticketing --eval "printjson(db.ticket_details.getIndexes().map(i=>i.key)); printjson(db.runCommand({connectionStatus:1}).authInfo.authenticatedUserRoles)"'
```

---

## Questions

### Q1. `ENABLE` vs `FORCE ROW LEVEL SECURITY`: why does this system need `FORCE`, and who still bypasses it? ★★★★★
**30-second headline:** The app user owns the tables, and owners bypass RLS unless FORCE is set; superusers and BYPASSRLS roles still bypass, and an owner can disable RLS, so production splits owner and app roles.
**Weak answer (what fails):** "RLS protects against everyone."
**Would I do it again?** Yes to FORCE; in production I would also separate the migration owner from the runtime role from the start.
**Strong answer:** `ticketing_app` **owns** the tables (Flyway runs as it), and owners bypass RLS unless
`FORCE` is set; without it, RLS would protect nothing for the application. Still bypassing: superusers
(`postgres`) and roles with `BYPASSRLS`. The owner can also `ALTER TABLE … DISABLE ROW LEVEL SECURITY`,
so a compromised app user could switch it off: the production fix is a separate migration owner and a
non-owner app role.
**Prove it:** the `pg_class` and `pg_roles` queries above; the RLS integration test uses a non-superuser
probe role precisely because the Testcontainers user is a superuser.

### Q2. What happens if the application forgets to set `app.tenant_id`? And if it sets the wrong one? ★★★★
**30-second headline:** Forgetting the tenant fails closed (no rows, inserts rejected); setting the wrong one is faithfully honoured, which is why the tenant must come only from the verified token.
**Weak answer (what fails):** "RLS catches wrong-tenant bugs."
**Strong answer:** Forgotten: `current_setting(..., true)` = NULL → `tenant_id = NULL` is never true →
no rows visible, inserts fail `WITH CHECK` → fail closed (errors/empty lists, no leak). Wrong one: RLS
faithfully shows that tenant: RLS can't detect an application bug in *which* tenant it binds. That's
why the tenant must come only from the verified token (`TenantResolver`) and why Hibernate `@TenantId`
uses the same source; RLS is the last line, not the only line.

### Q3. Would RLS with `set_config(..., true)` work behind PgBouncer in transaction-pooling mode? Session mode? ★★★★★
**30-second headline:** Transaction pooling works because set_config is transaction-local; but server-side prepared statements break unless prepareThreshold=0 or PgBouncer ≥ 1.21 with prepared-statement support.
**Weak answer (what fails):** Answering only the RLS part and missing prepared statements.
**Strong answer:** Transaction pooling: yes, *because* the setting is transaction-local, so it lives and
dies with the transaction that runs on one server connection. Session-level `SET` would be dangerous:
the next client's transaction on that server connection would inherit the tenant. Session pooling:
both work, but local is still safer. Same reasoning applies to HikariCP reuse inside the app.
**Follow-ups / traps:** "Anything else breaks in transaction pooling?" Yes: **server-side prepared
statements**. pgjdbc switches to named prepared statements after `prepareThreshold` (default 5)
executions; in transaction mode the next transaction may land on a different server connection where that
statement does not exist (`prepared statement "S_1" does not exist`). Fix: `prepareThreshold=0` in the JDBC
URL (costs some parse time), or PgBouncer ≥ 1.21 with `max_prepared_statements` (protocol-level support).
Also unusable in transaction mode: session `SET`, advisory session locks, `LISTEN/NOTIFY`, `WITH HOLD` cursors.

### Q4. Dual write: enumerate every failure point in `create` and `approve`, and the resulting state. ★★★★
**30-second headline:** Every failure point now ends in a recoverable state: the event commits with the state change in Postgres, Mongo is updated immediately and retried by the relay; the old compensation path and silent loss are gone.
**Weak answer (what fails):** "It's transactional across both databases."
**Would I do it again?** Yes; the dual-write window was the system's biggest correctness risk.
**Since implemented:** implemented on 2026-10-05 (V4 outbox, OutboxPublisher, idempotent DocumentStore).
**Strong answer (tell it as before → after):**
*Before (compensation design):* create wrote Mongo first, then Postgres; a Postgres failure deleted the
Mongo doc (a failed delete left an orphan); a crash between the two left an orphan; on approve, the
Postgres commit succeeded and the Mongo append could fail (WARN only), or silently match nothing
(`updateFirst` result ignored) → status APPROVED with history missing.
*After (transactional outbox, implemented):* every state change and its history event commit in **one
PostgreSQL transaction** (`ticket_outbox`). Failure points now: (1) Postgres commit fails → nothing
happened, error to user. (2) Commit ok, immediate Mongo projection fails → event stays pending with
`attempts`/`last_error`, the relay retries every 5 s, metrics + alerts fire (`OutboxBacklogGrowing`,
`OutboxEventsDead`). (3) Crash after commit → relay picks the event up on restart. (4) Mongo document
lost → replay the ticket's events from the outbox (idempotent by event id). Users may see history a few
seconds late; it is never lost within the 30-day retention window.
**Prove it:** `TicketFlowIntegrationTest.lostProjectionIsDetectedAndRebuiltFromTheOutbox`;
`kubectl -n ticketing exec postgres-0 -c postgres -- psql -U postgres -d ticketing -c "select count(*) filter (where published_at is null) as pending from ticket_outbox"`.

### Q5. Design the transactional outbox for this system concretely. ★★★★
**30-second headline:** Outbox row in the same transaction, relay with retries and dead-lettering, idempotent apply keyed by event id, ordering by time/version, metrics for backlog and failures, retention-based purge.
**Weak answer (what fails):** Describing CDC tooling without idempotency or ordering.
**Would I do it again?** Yes; next step for multiple replicas is FOR UPDATE SKIP LOCKED in the relay.
**Since implemented:** this design is now implemented (see ticket-core OutboxStore/OutboxPublisher).
**Strong answer:** Table `outbox(id, aggregate_id, type, payload jsonb, created_at, published_at)`
with the same RLS policy (or a separate schema owned by the relay). `TicketService` writes workflow row
+ outbox row in one Postgres transaction. A relay (poller with `FOR UPDATE SKIP LOCKED`, or Debezium
CDC) applies events to Mongo with idempotency key `(ticket_id, version)` and `$push` only if not
present. Ordering per aggregate by version; at-least-once delivery; poison-message handling; metrics on
lag. Creation of the document becomes an upsert from the `CREATED` event.

### Q6. The `events` array grows forever inside one document. When does that bite, and how would you remodel? ★★★★
**30-second headline:** Embedded history grows towards the 16 MB limit and bloats list payloads; bucket events into a separate collection and project summaries for lists.
**Weak answer (what fails):** "Mongo handles big documents."
**Strong answer:** 16 MB document limit, growing write amplification (whole document rewritten/moved in
some engines), large reads for list views (we project? Currently full documents are loaded for lists,
so history inflates list payloads). Remodel: cap embedded history (last N) + separate `ticket_events`
collection keyed by `(ticketId, at)` (bucket pattern), and list views that project only summary fields.

### Q7. MongoDB has no RLS. How strong is the isolation here and how would you strengthen it? ★★★★
**30-second headline:** Isolation in MongoDB is application-enforced in one class; strengthen with per-tenant databases/users, field-level encryption with per-tenant keys, or filtered views.
**Weak answer (what fails):** "MongoDB has role-based access, so it's isolated."
**Strong answer:** Isolation relies on code discipline in one class (`DocumentStore`) + the tenant from
the security context + JPMS hiding the class. Strengthen: database- or collection-per-tenant with
per-tenant users (bridge model), client-side field-level encryption with per-tenant keys (CSFLE /
Queryable Encryption), MongoDB views with filters per tenant user, or Atlas App Services rules.
Trade-off: connection/user management per tenant.

### Q8. A tenant leaves and invokes the right to erasure. What exactly do you delete, where, and what about backups? ★★★★
**30-second headline:** Delete across Postgres, Mongo, the outbox, Keycloak memberships and logs; handle backups by retention or crypto-shredding; prove it with queries and record it.
**Weak answer (what fails):** Deleting only the main table.
**Since implemented:** the outbox keeps ticket details for 30 days, so it is part of the erasure scope.
**Strong answer:** Postgres `ticket_workflow` rows + tenant row; Mongo documents; Keycloak groups and
user memberships (users may belong to other tenants: remove membership, not necessarily the user);
logs containing emails (retention-limited); backups: documented retention + crypto-shredding (per-tenant
keys) or accept expiry-based erasure, recorded in the RoPA. Verify by queries in both stores.
In the pool model this is a scripted, tested procedure, not ad-hoc SQL.

### Q9. One tenant generates 80% of the load. Options? ★★★★
**30-second headline:** Tenant-leading indexes exist; add per-tenant quotas, replicas for reads, partitioning, sharding, and finally silo the tenant.
**Weak answer (what fails):** Scaling the whole database for one tenant.
**Since implemented:** per-tenant quotas are implemented.
**Strong answer:** Indexes already lead with `tenant_id`; next: per-tenant rate limits (Kong per user
today, add per-tenant), connection pool quotas, read replicas for approver lists, partition
`ticket_workflow` by tenant (list partitioning; RLS works per partition), Mongo sharding with
`{tenantId, _id}` shard key (hashed or ranged), and finally move the tenant to a silo deployment.

### Q10. Backups and recovery objectives for this data. What RPO/RTO is realistic and how do you test it? ★★★★
**30-second headline:** Production: PITR with minute-level RPO, both stores restorable to the same time or reconciled, quarterly drills. Locally now: WAL archiving + oplog slices (RPO ≤ 5 min) and a passing restore drill.
**Weak answer (what fails):** "We have backups" without ever restoring.
**Would I do it again?** Yes; the restore drill found a real PostgreSQL behaviour (a recovery target needs a later commit) that would have surprised us during an incident.
**Since implemented:** implemented locally on 2026-10-05 (scripts/restore-drill.sh).
**Strong answer:** Today: none (laptop). Production: Postgres PITR (RPO minutes, RTO < 1 h for
moderate size), Mongo continuous backup/oplog; both stores must be restorable to the **same point in
time** or reconciled (outbox replay helps). Test quarterly: restore into an isolated environment, run
reconciliation and e2e, measure time. Keycloak DB is part of the restore set (users/groups).

### Q11. Rapid fire
* Index used by "my tickets"? → `ix_ticket_tenant_owner (tenant_id, owner_email, created_at DESC)`.
* Why `version bigint` default 0? → optimistic locking via `@Version`.
* Same id in both stores? → yes: Postgres `id` uuid = Mongo `_id` string.
* What enforces "owner can't hold the lock" at the DB? → `ck_owner_not_approver` (`lower(locked_by) <> lower(owner_email)`).
* Is the Mongo connection encrypted in-cluster? → no (NetworkPolicy + auth only), listed gap; TLS on AWS.
