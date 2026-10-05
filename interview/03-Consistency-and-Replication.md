# Consistency, PACELC and replication lag with PostgreSQL and MongoDB

## Concepts you must own

* **CAP:** during a network **P**artition a replicated store chooses **C**onsistency (refuse some
  requests) or **A**vailability (answer, possibly stale/divergent).
* **PACELC:** *if P* → A or C; *Else* (normal operation) → **L**atency or **C**onsistency.
  Synchronous replication pays latency on every write even when nothing is broken; async replication
  is fast but readers can be behind and failover can lose data.
* **PostgreSQL replication:** physical **streaming replication** (WAL shipped to standbys; hot standby
  serves read-only queries), replication **slots** (primary keeps WAL until consumed), **logical
  replication** (row changes per table/publication, PG15+ row filters), `synchronous_commit` levels:
  `off` → `local` → `remote_write` → `on` (remote flush) → `remote_apply` (visible on standby),
  together with `synchronous_standby_names` (`FIRST n (...)`, `ANY n (...)`).
* **MongoDB replication:** replica set (primary + secondaries, oplog), elections, **write concern**
  (`w:1`, `w:"majority"`, `w:<n>`, `wtimeout`, `j`), **read concern** (`local`, `majority`,
  `snapshot`, `linearizable`), **read preference** (`primary`, `primaryPreferred`, `secondary`,
  `nearest`, `maxStalenessSeconds`), **causally consistent sessions** (`afterClusterTime`), rollback
  of non-majority writes after failover, change streams (oplog-based, replica set only).
* **Session guarantees:** read-your-writes, monotonic reads, monotonic writes, writes-follow-reads.
* **Cross-store consistency:** no atomic commit across PostgreSQL and MongoDB; the "lag" between
  the stores is an application concern (compensation, outbox, CDC, reconciliation).

### PACELC classification

| Configuration | If partition | Else |
|---|---|---|
| PostgreSQL primary + **async** standbys | **PC** (minority side cannot write; standbys stay read-only) | **EL** (standby reads can be stale) |
| PostgreSQL with `synchronous_commit=remote_apply` + sync standby | **PC** (commits block if the sync standby is unreachable) | **EC** (standby reads see committed data; every commit waits) |
| MongoDB `w:"majority"` + `readConcern:"majority"` + `readPreference:primary` | **PC** (minority primary steps down) | **EC** |
| MongoDB `w:1` + `readPreference:secondary` | PA-like (acknowledged writes can be rolled back) | **EL** (stale secondary reads) |
| This system's JWT validation | AP by design (local keys) | EL (revocation visible at token expiry) |
| This system's Kong `local` rate counters | AP (each node counts alone) | EL |

## Local (narrowed) vs Production (unwrapped)

| Aspect | Local k3d (current, unchanged) | Production target (proposal, needs approval) |
|---|---|---|
| PostgreSQL topology | single instance by decision (**0 replicas**), WAL archiving (gzip, â¤ 5 min) + one base backup; PITR proven by `scripts/restore-drill.sh` | RDS PostgreSQL **Multi-AZ** (synchronous standby, zero data loss on single-AZ failure) + optional async **read replica** for approver lists |
| MongoDB topology | single instance as a **1-member replica set** (`rs0`): oplog, change streams and transactions available; oplog slices every 5 min for PITR | 3-member replica set (Atlas) or DocumentDB cluster; `w:"majority"` (default since 5.0), primary reads for user-facing screens |
| Replication lag | none possible (no replicas) | monitored per replica; reads routed away from replicas over budget |
| Read-your-writes | trivially true (single node) | enforced: commands on primary, LSN fence or primary-read window after a write; Mongo causal sessions |
| Cross-store consistency | transactional outbox in Postgres; immediate projection + relay retries; backlog/dead-event alerts | transactional **outbox** in Postgres → idempotent projector to Mongo (Postgres = source of truth); nightly reconciliation alert |
| Failover data loss | no failover (single instance); restore from backup with RPO â¤ 5 min | RPO ≈ 0 for Multi-AZ; async replica promotion RPO = its lag; Mongo `w:majority` prevents rollback of acknowledged writes |
| Validation | `docs/03` §3.4 reconciliation counts | lag dashboards + alerts, reconciliation job, game days with failover |

## Prove it

**On the local system (read-only checks):**

```bash
P() { kubectl -n ticketing exec postgres-0 -c postgres -- psql -U postgres -d ticketing -tAc "$1"; }
P "select count(*) from pg_stat_replication"                                     # 0: single instance by decision
P "select archived_count, last_archived_wal, last_failed_wal from pg_stat_archiver"   # WAL archiving for PITR
kubectl -n ticketing exec mongo-0 -c mongo -- sh -c 'mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin --eval "print(rs.status().set, rs.status().members.length, rs.status().members[0].stateStr)"'   # rs0 1 PRIMARY
bash scripts/restore-drill.sh all                                                # point-in-time restore of both stores, verified
```

**Lab: reproduce lag safely** (throwaway containers on a private Docker network, **not** the
ticketing cluster; validated 2026-10-05; delete afterwards):

```bash
export MSYS_NO_PATHCONV=1
docker network create lagdemo

# --- MongoDB: 3-member replica set, freeze one secondary to create lag
for n in 1 2 3; do docker run -d --name m$n --network lagdemo mongo:7 --replSet rs0 --bind_ip_all; done; sleep 6
docker exec m1 mongosh --quiet --eval 'rs.initiate({_id:"rs0",members:[{_id:0,host:"m1:27017",priority:2},{_id:1,host:"m2:27017"},{_id:2,host:"m3:27017"}]})'
sleep 15                                                                    # election
docker exec m2 mongosh --quiet --eval 'db.fsyncLock()'                      # m2 stops applying the oplog
docker exec m1 mongosh --quiet --eval 'db.getSiblingDB("t").c.insertOne({_id:1,status:"APPROVED"},{writeConcern:{w:"majority",wtimeout:5000}})'   # OK (m1+m3)
docker exec m1 mongosh --quiet --eval 'db.getSiblingDB("t").c.insertOne({_id:2},{writeConcern:{w:3,wtimeout:2000}})'                            # WriteConcernFailed
docker exec m2 mongosh --quiet --eval 'db.getMongo().setReadPref("secondary"); db.getSiblingDB("t").c.findOne({_id:1})'                         # null  -> stale read
docker exec m1 mongosh --quiet --eval 'rs.printSecondaryReplicationInfo()'                                                                       # m2: "N secs behind the primary"
docker exec m2 mongosh --quiet --eval 'db.fsyncUnlock()'                                                                                         # catches up

# --- PostgreSQL: primary + streaming standby, pause replay to create lag
docker run -d --name pg1 --network lagdemo -e POSTGRES_PASSWORD=lab postgres:16 -c wal_level=replica -c max_wal_senders=5 -c hot_standby=on; sleep 8
docker exec pg1 sh -c "echo 'host replication all all scram-sha-256' >> /var/lib/postgresql/data/pg_hba.conf"; docker exec pg1 psql -U postgres -c "select pg_reload_conf()"
docker run -d --name pg2 --network lagdemo --user postgres -e PGPASSWORD=lab --entrypoint bash postgres:16 \
  -c 'pg_basebackup -h pg1 -U postgres -D /var/lib/postgresql/data/r -R -X stream -C -S replica1 && exec postgres -D /var/lib/postgresql/data/r'; sleep 10
Q1() { docker exec pg1 psql -U postgres -tAc "$1"; }; Q2() { docker exec pg2 psql -U postgres -tAc "$1"; }
Q1 "select application_name, state, sync_state from pg_stat_replication"     # walreceiver | streaming | async
Q1 "create table t(id int primary key, status text); insert into t values (1,'OPEN')"
Q2 "select pg_wal_replay_pause()"                                             # replica stops applying WAL
Q1 "update t set status='APPROVED' where id=1"
Q1 "select status from t"; Q2 "select status from t"                          # APPROVED vs OPEN (stale read)
Q1 "select pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn), replay_lag from pg_stat_replication"   # bytes behind > 0, replay_lag may look tiny!
Q1 "alter system set synchronous_standby_names='*'"; Q1 "select pg_reload_conf()"
timeout 6 docker exec pg1 psql -U postgres -c "set synchronous_commit=remote_apply; update t set status='REJECTED' where id=1"; echo "exit $?"   # 124: commit waits for the standby
Q2 "select pg_wal_replay_resume()"; sleep 2; Q2 "select status from t"        # REJECTED: the "timed-out" commit had committed locally
Q2 "insert into t values (2,'x')"                                             # ERROR: read-only transaction (hot standby)

docker rm -f m1 m2 m3 pg1 pg2; docker network rm lagdemo
```

Observed results: `w:"majority"` succeeded with one secondary frozen; `w:3` → `WriteConcernFailed`;
lagging secondary returned `null` while the healthy one returned the document; PostgreSQL standby
served `OPEN` while the primary had `APPROVED`; LSN diff = 120 bytes while `replay_lag` showed ~2 ms;
the `remote_apply` commit blocked until killed, **and was still committed** (visible after resume).

---

## Questions

### Q1. Classify PostgreSQL and MongoDB in this system with PACELC, locally and in the production target. ★★★★★
**30-second headline:** Per data path, not per product: lock decisions are PC/EC on the primary; lists can be EL on replicas with a staleness budget; JWT validation and rate counters are deliberately AP. Locally: single instances, so the choice is moot.
**Weak answer (what fails):** Classifying "PostgreSQL is CP" without the else-branch or per-path nuance.
**Since implemented:** locally MongoDB is now a one-member replica set (oplog) and PostgreSQL archives WAL; still single instances by decision.
**Strong answer:** Locally: single nodes, so there is no partition tolerance to choose about; reads
are trivially consistent; availability = one pod. Production: Postgres Multi-AZ = PC/EC for the
primary path (sync standby; not readable in classic Multi-AZ), async read replica = EL for the reads
routed to it. Mongo replica set with `w:majority`, primary reads = PC/EC; secondary reads for
analytics = EL. The choice is **per data path**, not per product: lock decisions must be PC/EC;
lists can be EL with a staleness budget.
**Follow-ups / traps:** "Is a Multi-AZ RDS standby a read replica?" (classic Multi-AZ: no; Multi-AZ
DB *cluster*: readable standbys.)

### Q2. In the lab, `replay_lag` showed ~2 ms while the standby was clearly stale. Explain, and tell me what you would alert on. ★★★★★
**30-second headline:** replay_lag is feedback time and can look tiny while a paused standby is stale; alert on LSN bytes behind, replication state and slot retention instead.
**Weak answer (what fails):** Trusting replay_lag or now() - last_replay_timestamp blindly.
**Strong answer:** `replay_lag` is a *time* measured from feedback the standby sends; when replay is
paused (or the primary is idle) it does not grow the way you expect, and on an idle primary
`now() - pg_last_xact_replay_timestamp()` grows even though nothing is missing. The robust signal is
**bytes behind**: `pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)` per standby, plus state
(`streaming`) and slot retention (`pg_replication_slots`, `wal_status`). Alert on bytes behind and
on replay being stalled (LSN not advancing while the primary advances), and on slots retaining too
much WAL (disk-full risk on the primary).
**Local:** no replicas, nothing to alert. **Production:** CloudWatch `ReplicaLag` plus LSN-based
custom metric; route reads away from a replica over budget.

### Q3. The `remote_apply` commit "timed out" but the update was there afterwards. Why, and what does it mean for API semantics? ★★★★★
**30-second headline:** PostgreSQL commits locally before waiting for the synchronous standby; cancelling the wait doesn't roll back. Treat write errors as ambiguous: idempotent commands and re-read state.
**Weak answer (what fails):** "Timeout means the transaction failed."
**Strong answer:** In PostgreSQL the transaction commits **locally first**, then the backend waits for
the synchronous standby's confirmation. Cancelling the wait (client timeout, `pg_cancel_backend`)
does not roll back: the commit already happened; PostgreSQL only warns that it may not have
replicated. So a client that saw an error may have actually succeeded. API consequences: make
commands **idempotent** (ticket version / idempotency key), let clients re-read state after an
ambiguous failure, and never assume "error = nothing happened" for writes. Same principle in MongoDB:
`WriteConcernFailed` (as with `w:3` in the lab) means *not yet acknowledged by enough members*, not
"not written"; the write is on the primary and may still replicate.

### Q4. You add a read replica for the approver queue. An approver picks up a ticket and the list still shows it OPEN. Fix it without giving up the replica. ★★★★★
**30-second headline:** Return the updated resource from the write, fence reads by LSN or pin the user to the primary briefly, and never read lock state for decisions from a replica; optimistic locking still prevents wrong writes.
**Weak answer (what fails):** "Just add sleep before the reload."
**Strong answer:** Read-your-writes violation. Options: (1) the API already returns the updated
`TicketView` from `claim`, so the UI should render that instead of re-reading; (2) **LSN fence**: after
a write, return `pg_current_wal_lsn()`; the next read goes to a replica only if
`pg_last_wal_replay_lsn() >= fence`, else primary; (3) sticky-primary window per user after a write;
(4) show staleness. **Never** route `claim/unlock/decide` reads to a replica; optimistic locking
(`@Version`) would still prevent a wrong write (409) but the UX would suffer. Spring:
`AbstractRoutingDataSource` + `@Transactional(readOnly = true)` → replica, with
`LazyConnectionDataSourceProxy`.
**Local:** single node: no issue. **Production:** routing + fence + tests that write-then-read.

### Q5. Same problem in MongoDB: the approver reads ticket history from a secondary and misses their own event. Options? ★★★★
**30-second headline:** Read user-facing history from the primary, or use a causally consistent session (afterClusterTime) with majority read/write concerns.
**Weak answer (what fails):** Using readPreference secondary everywhere for speed.
**Strong answer:** Read from the primary for user-facing reads (simplest), or use a **causally
consistent session**: reads in the same session carry `afterClusterTime` and a secondary waits until
it has applied that point (with `readConcern: majority`, write `w: majority`). Or
`maxStalenessSeconds` for reads that tolerate bounded staleness. The lab shows the hazard: the lagging
secondary returned `null` for a document the primary had acknowledged with `w:majority`.

### Q6. Postgres fails over to an async replica that was 2 s behind. Mongo was not affected. What state are the two stores in, and how do you recover? ★★★★★
**30-second headline:** Postgres lost ~2 s; Mongo may hold events for commits that no longer exist. Postgres is the source of truth: reconcile by (ticket, version) and rebuild the projection from the outbox; prevent with a synchronous standby.
**Weak answer (what fails):** Assuming both stores roll back together.
**Would I do it again?** Yes to Postgres-as-source-of-truth; that is exactly why the outbox was worth building.
**Since implemented:** the projection can now be rebuilt from the outbox (proven by an integration test).
**Strong answer:** The last ~2 s of Postgres commits are gone (e.g. an APPROVE the user saw succeed),
but Mongo may already hold the corresponding history events → Mongo says APPROVED, Postgres says
LOCKED. Recovery: Postgres is the **source of truth** for workflow state; detect divergence by
reconciliation (events whose `(ticketId, version)` do not exist in Postgres); mark or compensate those
events, notify affected users. Prevention: synchronous standby (Multi-AZ) for zero loss on single
failures; outbox so Mongo is a projection that can be **rebuilt** from Postgres.

### Q7. MongoDB `w:1` vs `w:"majority"`: what exactly can go wrong with `w:1`, and what's the latency cost of majority? ★★★★
**30-second headline:** w:1 acknowledges before replication, so failover can roll back acknowledged writes; w:majority costs one round trip to a secondary and is the default since 5.0.
**Weak answer (what fails):** "w:1 is fine with a replica set."
**Strong answer:** With `w:1` the primary acknowledges before secondaries have the write; if it then
fails, a secondary is elected and the old primary's un-replicated writes are **rolled back** (written
to rollback files): acknowledged data lost. `w:"majority"` waits for a majority to persist (one
network round trip to the nearest secondary in a 3-member set, plus journal). Since MongoDB 5.0
majority is the default (confirmed in the lab: `{"w":"majority"}`).

### Q8. Where does this application *need* strong consistency and where is eventual consistency acceptable? Defend each. ★★★★
**30-second headline:** Strong for locks, decisions, tenant registry and SoD; eventual with a stated bound for lists, history projection, rate counters, role changes and certificates.
**Weak answer (what fails):** "Everything must be strongly consistent."
**Since implemented:** history now has a bound and a guarantee (outbox + alerts), no longer "may be lost".
**Strong answer:** Strong (primary, PC/EC): lock acquisition and decisions (correctness; optimistic
locking on the primary), tenant registry checks, separation-of-duties constraint. Eventual with a
bound: approver/applicant list views (seconds), history display (if projected via outbox), rate-limit
counters (approximate), role changes (≤ token lifetime 300 s), certificates (renewal → reload).
Unacceptable today: history append has **no bound** (it can be lost): the outbox fixes that.

### Q9. Without changing the current design, what would you check before claiming "the two stores are consistent"? ★★★★
**30-second headline:** Reconciliation counts, spot checks of latest event vs status, outbox backlog = 0, and zero publish failures; evidence, not a guarantee.
**Weak answer (what fails):** "They're consistent because the code writes both."
**Since implemented:** outbox metrics and alerts now make divergence visible.
**Strong answer:** Reconciliation (`docs/03` §3.4: per-tenant counts identical), spot checks that
every Postgres ticket has a Mongo document and that each document's latest event matches the
Postgres status, and the log count of "could not be written to MongoDB" warnings (0 in the last 48 h
when checked). That's evidence, not a guarantee: the design can still diverge silently
(Audit-and-Alerting S1/S2).

### Q10. Rapid fire
* Can a hot standby accept writes? → no (lab: "cannot execute INSERT in a read-only transaction").
* Default Mongo write concern since 5.0? → `majority` (lab confirmed).
* Better Postgres lag metric than `replay_lag`? → LSN bytes behind (`pg_wal_lsn_diff`).
* Why was the local Mongo converted to a 1-member replica set? → the oplog enables PITR backups, change streams and transactions at the same footprint.
* What prevents a lost update between two approvers even with a stale read? → `@Version` check on the primary.
