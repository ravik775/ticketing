# 3. Investigating the databases (PostgreSQL and MongoDB)

The application stores each ticket in **two** databases. This guide shows how to look inside both
safely, with ready-to-use queries.

| Database | Pod | Holds |
|---|---|---|
| **PostgreSQL** | `postgres-0` (namespace `ticketing`) | the *workflow* of each ticket: tenant, owner, status, who holds the lock, timestamps, version. Also the tenant registry and Keycloak's own data (separate database `keycloak`). |
| **MongoDB** | `mongo-0` (namespace `ticketing`) | the *content* of each ticket: title, mobile number, description, and the full history/comments. |

The same ticket **id** (a UUID such as `4c11dc58-be9b-4feb-9526-0aea18ead481`) is used in both:
`ticket_workflow.id` in PostgreSQL and `_id` in MongoDB.

> **Golden rules**
> 1. **Look, don't touch.** Only run `SELECT` (PostgreSQL) and `find`/`aggregate`/`count` (MongoDB)
>    unless a change has been agreed. A wrong `UPDATE`/`DELETE` can only be undone by a point-in-time
>    restore (section 3.6), which is slow and affects every tenant.
> 2. Ticket data contains **personal data** (emails, mobile numbers). Do not copy it into chats,
>    tickets or e-mails.
> 3. The databases are reachable **only** from inside the cluster (NetworkPolicy). The commands below
>    go through `kubectl`, which requires administrator access to the cluster.

Start every session with [Before you start](README.md#before-you-start-do-this-once-per-terminal-window).

---

## 3.1 PostgreSQL: connecting

There are two database users. Choose deliberately:

| User | Sees | Use it to |
|---|---|---|
| `ticketing_app` (the application's own user) | **only the tenant you select** (Row-Level Security) | see exactly what the application sees for one tenant |
| `postgres` (administrator) | **everything**, all tenants | cross-tenant investigation, tenant registry, schema |

### Run one query (recommended for beginners)

```bash
kubectl -n ticketing exec postgres-0 -- psql -U postgres -d ticketing -c "SELECT * FROM tenant;"
```

### Open an interactive session

```bash
winpty kubectl -n ticketing exec -it postgres-0 -- psql -U postgres -d ticketing
```

(`winpty` is needed in Git Bash for interactive programs; on macOS/Linux leave it out.) You get the
prompt `ticketing=#`. Type a query ending with `;` and press Enter. Useful commands inside `psql`:
`\dt` lists tables, `\d ticket_workflow` describes a table, `\x` switches to a vertical layout for
wide rows, `\q` quits.

### See the data as the application sees it (Row-Level Security)

The `ticket_workflow` table is protected by **Row-Level Security**: for the application user, a query
returns nothing unless a tenant is selected first, and then only that tenant's rows.

```bash
kubectl -n ticketing exec postgres-0 -- sh -c 'PGPASSWORD=$TICKETING_DB_PASSWORD psql -h 127.0.0.1 -U ticketing_app -d ticketing \
  -c "SELECT count(*) AS without_tenant FROM ticket_workflow;" \
  -c "BEGIN" \
  -c "SELECT set_config('"'"'app.tenant_id'"'"', '"'"'acme'"'"', true)" \
  -c "SELECT status, count(*) FROM ticket_workflow GROUP BY status" \
  -c "COMMIT"'
```

Expected: `without_tenant` is **0** (fail-closed), then the acme counts. This is also the quickest proof
that tenant isolation is working at the database level.

## 3.2 PostgreSQL: useful queries

Run each with `kubectl -n ticketing exec postgres-0 -- psql -U postgres -d ticketing -c "<query>"`.

| Question | Query |
|---|---|
| Latest tickets | `SELECT id, tenant_id, owner_email, status, locked_by, updated_at FROM ticket_workflow ORDER BY updated_at DESC LIMIT 20;` |
| Tickets per tenant and status | `SELECT tenant_id, status, count(*) FROM ticket_workflow GROUP BY 1,2 ORDER BY 1,2;` |
| One ticket | `SELECT * FROM ticket_workflow WHERE id = '<ticket-id>';` |
| Tickets of one person | `SELECT id, tenant_id, status, created_at FROM ticket_workflow WHERE owner_email = 'alice@ticketing.test' ORDER BY created_at DESC;` |
| Locked tickets and for how long | `SELECT id, owner_email, locked_by, now() - locked_at AS locked_for FROM ticket_workflow WHERE status = 'LOCKED';` |
| Tickets waiting for the applicant | `SELECT id, owner_email, updated_at FROM ticket_workflow WHERE status = 'MORE_INFO';` |
| Registered tenants | `SELECT * FROM tenant;` |
| Database schema version | `SELECT version, description, success, installed_on FROM flyway_schema_history;` |
| Database size | `SELECT pg_size_pretty(pg_database_size('ticketing'));` |
| Active connections | `SELECT usename, application_name, state, query_start FROM pg_stat_activity WHERE datname = 'ticketing';` |

**Column meanings (`ticket_workflow`):** `status` is one of `OPEN`, `LOCKED`, `MORE_INFO`, `APPROVED`,
`REJECTED`. `locked_by` is filled only while `LOCKED`. `version` increases on every change (it stops
two approvers changing the same ticket at the same moment). Database rules (`CHECK` constraints)
guarantee that a locked ticket always has a lock holder and that the owner can never hold the lock.

**A stuck lock** (approver on holiday): the normal fix is in the application: any other approver of
the tenant clicks **Unlock**. Do not edit the row by hand.

## 3.3 MongoDB: ticket details and history

### Run one command

```bash
kubectl -n ticketing exec mongo-0 -- sh -c 'mongosh --quiet -u ticketing_app -p "$MONGO_APP_PASSWORD" \
  --authenticationDatabase ticketing ticketing --eval "db.ticket_details.countDocuments({})"'
```

The password is read inside the pod from its environment, so you never type it. `ticketing_app` can
read and write only the `ticketing` database.

### Open an interactive session

```bash
winpty kubectl -n ticketing exec -it mongo-0 -- sh -c 'mongosh -u ticketing_app -p "$MONGO_APP_PASSWORD" --authenticationDatabase ticketing ticketing'
```

The prompt is `ticketing>`. Type `exit` to leave.

### Useful queries (type them at the `ticketing>` prompt)

```javascript
// one ticket, including its full history
db.ticket_details.findOne({ _id: "4c11dc58-be9b-4feb-9526-0aea18ead481" })

// latest 10 tickets of a tenant (only a few fields)
db.ticket_details.find({ tenantId: "acme" }, { title: 1, createdBy: 1, createdAt: 1 }).sort({ createdAt: -1 }).limit(10)

// tickets of one person
db.ticket_details.find({ createdBy: "alice@ticketing.test" }, { title: 1, tenantId: 1 })

// number of tickets per tenant
db.ticket_details.aggregate([{ $group: { _id: "$tenantId", tickets: { $sum: 1 } } }])

// tickets that were rejected (look inside the history)
db.ticket_details.find({ "events.type": "REJECT" }, { title: 1, tenantId: 1 })

// everything one approver did
db.ticket_details.find({ "events.actor": "carol@ticketing.test" }, { title: 1, "events.$": 1 })

// indexes
db.ticket_details.getIndexes()
```

**Document fields:** `_id` (ticket id), `tenantId`, `title`, `mobile`, `description`, `createdBy`,
`createdAt`, `events` (list of `{type, actor, comment, at}`; `type` is `CREATED`, `CLAIMED`,
`UNLOCKED`, `APPROVE`, `REJECT`, `REQUEST_INFO`, `RESPONDED`), and `_class` (used by the application).

> MongoDB has no row-level security of its own. Isolation is enforced by the application, which
> always adds `tenantId` to every query. When you query by hand **always include `tenantId`** if you
> are answering a question for one tenant.

## 3.4 Checking that both databases agree

There is no single transaction across two databases. Every change is first recorded in the
`ticket_outbox` table in PostgreSQL (same transaction as the change) and then copied to MongoDB; if
MongoDB was down, the copy is retried every 5 seconds until it succeeds. So after an incident, first
check the outbox, then compare counts.

```bash
# events not yet copied to MongoDB (should be 0, or a few for a few seconds)
kubectl -n ticketing exec postgres-0 -c postgres -- psql -U postgres -d ticketing -c \
  "SELECT count(*) AS pending, max(attempts) AS max_attempts, min(occurred_at) AS oldest FROM ticket_outbox WHERE published_at IS NULL;"
```

Rows with many attempts and a `last_error` are "dead" events: they raise the `OutboxEventsDead` alert
and need investigation (the error text says why).

```bash
# counts per tenant in PostgreSQL
kubectl -n ticketing exec postgres-0 -- psql -U postgres -d ticketing -tAc \
  "SELECT tenant_id || ' ' || count(*) FROM ticket_workflow GROUP BY tenant_id ORDER BY 1;"

# counts per tenant in MongoDB
kubectl -n ticketing exec mongo-0 -- sh -c 'mongosh --quiet -u ticketing_app -p "$MONGO_APP_PASSWORD" --authenticationDatabase ticketing ticketing \
  --eval "db.ticket_details.aggregate([{\$group:{_id:\"\$tenantId\",n:{\$sum:1}}},{\$sort:{_id:1}}]).forEach(d => print(d._id + \" \" + d.n))"'
```

The two lists should be identical. What a mismatch means:

| Symptom | Meaning | Effect for users |
|---|---|---|
| A ticket in PostgreSQL but not in MongoDB, with a pending outbox row | the copy has not happened yet (MongoDB down or slow) | details appear once the relay succeeds |
| A ticket in PostgreSQL but not in MongoDB, no pending outbox row | the MongoDB document was lost after it was written (e.g. restored from an older backup) | the ticket shows "(details unavailable)"; with agreement, set `published_at = NULL` on that ticket's outbox rows and the relay rebuilds it (copying is idempotent; rows are kept 30 days) |
| A ticket in MongoDB but not in PostgreSQL | should not happen any more (MongoDB is written only from the outbox) | investigate; harmless to users |
| History missing the latest step | the copy is still pending | the outbox row is pending; `OutboxBacklogGrowing` fires if it lasts |

## 3.5 Using a graphical tool (optional)

Tools such as **DBeaver** (PostgreSQL) or **MongoDB Compass** can connect through a temporary tunnel
that only exists while the command runs:

```bash
kubectl -n ticketing port-forward pod/postgres-0 15432:5432     # PostgreSQL on localhost:15432
kubectl -n ticketing port-forward pod/mongo-0 17017:27017       # MongoDB on localhost:17017
```

Leave that window open, connect the tool to `localhost` and the port shown, and press **Ctrl+C** when
finished. Passwords:

```bash
kubectl -n ticketing get secret postgres-credentials -o jsonpath='{.data.POSTGRES_PASSWORD}' | base64 -d; echo   # user postgres
kubectl -n ticketing get secret mongo-credentials -o jsonpath='{.data.MONGO_APP_PASSWORD}' | base64 -d; echo      # user ticketing_app, auth DB ticketing
```

For MongoDB Compass use the connection string
`mongodb://ticketing_app:<password>@localhost:17017/ticketing?authSource=ticketing&directConnection=true`.

> A port-forward bypasses the network rules for as long as it runs. Only open one when you need it,
> close it afterwards, and never leave it running on a shared machine.

## 3.6 Backups and point-in-time restore

Each database runs as **one instance** (a deliberate choice to save laptop resources). Instead of
replicas, a `backup` sidecar next to each database keeps **one backup copy** on its own volume:

| Database | What is kept | How often | Worst-case data loss (RPO) |
|---|---|---|---|
| PostgreSQL | one base backup (`pg_basebackup`) + every WAL file since it (compressed) | base daily; WAL at least every 5 min | 5 minutes |
| MongoDB (1-member replica set) | one full dump with its oplog + oplog slices since it | full daily; slice every 5 min | 5 minutes |

**Is the backup healthy?** Each sidecar writes a status line every 5 minutes, and the `BackupTooOld`,
`BackupFailing` and `PostgresWalArchivingFailing` alerts watch them:

```bash
kubectl -n ticketing logs postgres-0 -c backup --tail=3   # backup status: db=postgres base_age_seconds=... wal_files=...
kubectl -n ticketing logs mongo-0 -c backup --tail=3      # backup status: db=mongo base_age_seconds=... oplog_slices=...
```

**Prove it can be restored (restore drill).** The drill restores the backup into a throw-away pod with no
network access, rolls it forward to the chosen time, compares it with the live database and deletes the
pod. The live databases are never touched.

```bash
bash scripts/restore-drill.sh all                               # restore to "now"
bash scripts/restore-drill.sh postgres "2026-10-05 08:15:00"    # restore to a UTC time
```

Run it after every change to the backup set-up and at least monthly. A real restore (replacing the live
data) uses the same steps but must be agreed first: it rewinds **every tenant** to that time.

**Limits on the laptop:** the backup copy sits on the same node as the database, so losing the node or
the Docker volume loses both. In production the copy goes to object storage in another account with
object lock, and the databases are managed services with standby replicas (guide 7 and
[enterprise-gap.md](enterprise-gap.md)).
