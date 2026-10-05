# Spring Boot service design (Java 21, Spring Boot 3.5)

## Concepts you must own

* **Auto-configuration & conditions** (`@ConditionalOnClass/Bean/Property`), component scanning from
  the `@SpringBootApplication` package, profiles, externalised configuration precedence
  (env vars > profile YAML > default YAML).
* **SSL bundles** (Boot 3.1+): named PEM/JKS material reused by the web server and HTTP clients;
  `reload-on-update` for hot certificate rotation.
* **Transactions:** `@Transactional` proxies, propagation, read-only hints; `JpaTransactionManager`
  exposes the same JDBC connection to `JdbcTemplate` inside the transaction.
* **JPA/Hibernate:** `open-in-view` (off!), `ddl-auto` (none in prod; migrations own schema),
  optimistic locking (`@Version`), Hibernate 6 **discriminator multi-tenancy** with `@TenantId` +
  `CurrentTenantIdentifierResolver`.
* **Schema evolution:** Flyway versioned migrations, forward-only, run at startup.
* **JPMS (Java modules):** `exports` (compile-time API), `opens` (reflection), strong encapsulation;
  Spring Boot fat jars run on the **classpath** (unnamed module) so JPMS is a build-time guarantee.
* **Validation & errors:** Jakarta Bean Validation, built-in method validation for `@RequestParam`
  (Spring 6.1+), `ProblemDetail` (RFC 7807).
* **Testing pyramid:** unit, slice (`@WebMvcTest`), `@SpringBootTest` with Testcontainers.
* **Containers:** container-aware JVM (`MaxRAMPercentage`), non-root image, read-only FS, probes.

## How this application applies them

| Concept | Implementation | Where |
|---|---|---|
| Three-module build, enforced boundaries | `ticket-security`, `ticket-core` (exports only facade + DTOs), `ticket-api` | `module-info.java` ×3 |
| Root-package application class | `com.ticketing.TicketingApplication` so scanning/JPA/repos reach `core.internal` | `TicketingApplication.java` |
| Hibernate tenant filter | `@TenantId` on entity + resolver fed from `TenantContextHolder`; `__no_tenant__` when none | `TicketWorkflow`, `TenantIdentifierResolver` |
| DB session tenant for RLS | `set_config('app.tenant_id', ?, true)` (transaction-local) at the start of each `@Transactional` method | `WorkflowStore.bindTenant()` |
| Optimistic locking | `@Version` + `saveAndFlush` inside the transaction → `OptimisticLockingFailureException` → 409 | `WorkflowStore.update`, `TicketService.mutate` |
| Schema | Flyway V1–V3 (tables, RLS, seed tenants, SoD constraint) | `db/migration` |
| mTLS server | SSL bundle `server` (PEM from `/tls`), `client-auth: need` | `application-k8s.yml` |
| Config | profiles `k8s`, `test`; env overrides (`POSTGRES_URL`, `OIDC_ISSUER`, `TICKET_LOCK_TIMEOUT`) | `application*.yml` |
| Validation | records with constraints; `@Min/@Max` paging; `ProblemDetail` | DTOs, controllers |
| Image | Temurin 21 JRE, uid 10001, `MaxRAMPercentage=70`, read-only root FS + `/tmp` emptyDir | `ticket-api/Dockerfile`, `40-ticket-service.yaml` |
| Tests | 43 tests: unit, slice, Testcontainers (Postgres + Mongo), 0 skipped (the build fails on skips) | `src/test` |

## Local (narrowed) → Production (unwrapped)

> Local design unchanged; production items are **proposals requiring approval**.

| Q | Aspect | Local (narrowed) | Production (unwrapped) | Validate in production |
|---|---|---|---|---|
| Q1 | JPMS | compile-time boundary | same + ArchUnit; accepted that runtime is classpath | build gate |
| Q2 | RLS binding | single Postgres | same through PgBouncer/RDS; readOnly routing to replicas (03-Consistency Q4) | integration tests against RDS-like staging |
| Q3 | `@TenantId` + RLS | same | same | RLS test as non-owner role |
| Q4 | Concurrency | 1 replica | many replicas; optimistic locking unchanged | concurrent-claim load test |
| Q5 | OSIV | off | off | — |
| Q6 | Migrations | at startup as owner | Flyway Job before rollout, separate owner role | migration job logs, RLS still enforced for app role |
| Q7 | Zero-downtime schema | not needed | expand/contract, `NOT VALID` constraints | two-version compatibility test |
| Q8 | Cert reload | restart via `reload-certs.sh` | `reload-on-update: true` (or mesh) | served-serial check after renewal |
| Q9 | JVM sizing | 768 Mi limit, 1 replica | sized from load tests; NMT baseline | OOMKilled = 0, GC pause SLO |
| Q10 | Test reproducibility | laptop Docker | CI runners with Docker; skip = fail; Maven wrapper; JDK 21 | test counts trended |
| Q11 | Paging | OFFSET, ≤ 200 | keyset pagination | deep-page latency test |
| Q12 | Virtual threads | off | evaluate with load test; pool sizes remain the limit | p95 and DB saturation before/after |

## Prove it

```bash
MVN=./mvnw   # Maven wrapper in the repository root (pins Maven 3.9.16)
"$MVN" -B verify | grep -E "Tests run:|BUILD"                         # 43 tests incl. Testcontainers (Docker must run)
grep -n "exports\|opens" ticket-core/src/main/java/module-info.java     # only com.ticketing.core is exported
kubectl -n ticketing exec postgres-0 -- psql -U postgres -d ticketing -c "select version, description from flyway_schema_history"
kubectl -n ticketing exec deploy/ticket-service -- sh -c 'touch /app/x'   # Read-only file system
kubectl -n ticketing logs deploy/ticket-service | grep "Started TicketingApplication"
```

---

## Questions

### Q1. You claim JPMS is a security boundary. Spring Boot runs the fat jar on the classpath. Is the claim honest? ★★★★★
**30-second headline:** Honest answer: compile-time only. It stops the web layer depending on persistence classes, but at run time everything is on the classpath.
**Weak answer (what fails):** "JPMS secures the runtime."
**Would I do it again?** Yes; it is a cheap, effective fitness function, as long as nobody oversells it.
**Strong answer:** Partially, and saying so is the strong answer. The guarantee is **compile time**:
`ticket-api` cannot reference `com.ticketing.core.internal` (the build fails), so the web layer cannot
bypass `TicketService`. At run time everything is in the unnamed module and reflection can reach
anything. It's an *architectural fitness function* that prevents accidental coupling, not a sandbox
against malicious code. To enforce at run time you'd launch on the module path (custom launcher/jlink),
which conflicts with Boot's nested-jar loader and many reflective frameworks.
**Prove it:** add `import com.ticketing.core.internal.WorkflowStore;` to a controller → compile error.

### Q2. Explain exactly how the tenant reaches PostgreSQL RLS for one request, including which connection the `set_config` runs on. ★★★★★
**30-second headline:** Filter binds the tenant; @Transactional binds one connection; set_config(..., true) runs on that same connection and is reset at commit, so pooled connections never carry a tenant.
**Weak answer (what fails):** "set_config runs on whatever connection."
**Strong answer:** `TenantContextFilter` binds `TenantContext` (ThreadLocal) → controller →
`TicketService` → `WorkflowStore` method annotated `@Transactional` → `JpaTransactionManager` begins a
transaction and binds the JDBC connection to the thread (via `ConnectionHolder`, because
`HibernateJpaDialect` exposes it) → `JdbcTemplate` in `bindTenant()` uses **that same connection** to
run `set_config('app.tenant_id', tenant, true)` → JPA queries on that connection see the setting →
`is_local = true` means it is reset at commit/rollback, so pooled connections cannot carry a tenant to
the next borrower. If `set_config` ran outside the transaction, it would hit a different pooled
connection: a silent, fail-closed bug (no rows).
**Follow-ups / traps:** "Why `true` (local) and not session-level?" (session-level leaks across pool reuse.)

### Q3. Hibernate `@TenantId` *and* RLS: isn't that redundant? ★★★★
**30-second headline:** Deliberate defence in depth with different failure modes: Hibernate guards application queries, forced fail-closed RLS guards everything that bypasses Hibernate.
**Weak answer (what fails):** "Redundant, pick one."
**Strong answer:** Deliberate defence in depth with different failure modes. `@TenantId` protects
against application query mistakes (forgotten `where tenant_id`) and also sets the tenant on insert;
RLS protects against anything that bypasses Hibernate (native SQL, a future reporting job, SQL
injection, a bug in the resolver). RLS is **forced** (applies to the owner) and **fail-closed** (no
setting → no rows). The integration test probes RLS as a non-superuser because superusers bypass RLS.
**Prove it:** `TicketFlowIntegrationTest.rowLevelSecurityFailsClosedAndIsolatesTenants`.

### Q4. Two approvers click "Pick up" at the same millisecond. Walk through what happens. ★★★★
**30-second headline:** Both read version N; the first UPDATE ... WHERE version=N wins, the second updates 0 rows and becomes a 409; no lock is held during user think time.
**Weak answer (what fails):** "The database serialises them" without explaining how.
**Strong answer:** Both transactions read version N with status OPEN (READ COMMITTED). Both call
`claim()` in memory. First `saveAndFlush` issues `UPDATE … SET …, version=N+1 WHERE id=? AND
version=N` → 1 row. Second issues the same → 0 rows → Hibernate throws
`OptimisticLockException` → Spring translates to `OptimisticLockingFailureException` →
`TicketService.mutate` → `TicketConflictException` → 409 "modified by someone else; reload". If the
second transaction started after the first commit, it reads LOCKED and gets a domain 409 instead.
Either way exactly one winner, no DB lock held across user think time.
**Follow-ups / traps:** "Why not `SELECT … FOR UPDATE`?" (works, but pessimistic locks across retries
and connection pools reduce throughput; optimistic is right for low contention.)

### Q5. `open-in-view: false`: what does it prevent and what does it cost? ★★★
**30-second headline:** OSIV keeps persistence context and connections open for the whole request; off means fetch what you need inside the service.
**Weak answer (what fails):** Not knowing what OSIV is.
**Strong answer:** OSIV keeps a persistence context (and potentially a connection) open for the whole
web request, hiding lazy-loading in views and holding DB resources during serialisation/slow clients.
Off = connections held only inside transactions; cost: you must fetch what you need inside the service
(here views are built from explicit queries; no lazy associations).

### Q6. Why are migrations run by the application at startup, and when would you stop doing that? ★★★★
**30-second headline:** Simple and convergent at small scale; move migrations to a pre-deploy job when replicas, long migrations or owner/app role separation matter.
**Weak answer (what fails):** "Always run migrations at startup."
**Strong answer:** Simple, versioned, environments converge automatically. Stop when: multiple
replicas start concurrently (Flyway locks, but long migrations block startup and probes), DB
privileges should be split (migration user owns tables; app user must **not** own them so RLS can't be
disabled by the app), or zero-downtime changes need expand/contract choreography. Then run Flyway as a
Kubernetes Job/CI step before rollout.

### Q7. How do you make a schema change with zero downtime given two running versions of the service? ★★★★
**30-second headline:** Expand/contract over several releases, backfill in batches, add constraints NOT VALID then validate, keep both versions working at every step.
**Weak answer (what fails):** A single migration that renames a column.
**Strong answer:** Expand/contract: (1) add new column nullable / new table (compatible with old
code); (2) deploy code that writes both / reads new with fallback; (3) backfill in batches; (4) deploy
code that uses only new; (5) contract (drop old) in a later release. Constraints like V3's `CHECK`
must be validated against existing data first (`NOT VALID` then `VALIDATE CONSTRAINT` to avoid long
locks). RLS policies change atomically but must stay compatible with both versions.

### Q8. The service reads its TLS certificate once. cert-manager renews it. Fix it properly. ★★★★
**30-second headline:** Enable SSL bundle reload-on-update so Tomcat swaps certificates without restart; today reload-certs.sh restarts the service.
**Weak answer (what fails):** "cert-manager handles it" (it doesn't reload the app).
**Strong answer:** Spring Boot SSL bundles support hot reload: `spring.ssl.bundle.pem.server.reload-on-update: true`
(+ `spring.ssl.bundle.watch.file.quiet-period`), and Tomcat swaps the SSL context without restart. In
Kubernetes, secret volume updates are atomic symlink swaps; Boot watches the files. Today the gap is
handled by `scripts/reload-certs.sh` (rolling restart); verified that the served serial stays old
until restart.
**Prove it:** `docs/05` §5.5 serial comparison.

### Q9. Container sizing: memory limit 768 Mi, `MaxRAMPercentage=70`. Is that right? ★★★★
**30-second headline:** Heap ~537 MiB leaves ~230 MiB for metaspace, threads and buffers; verify with native memory tracking under load and watch OOMKilled vs OutOfMemoryError.
**Weak answer (what fails):** "70 % is the standard."
**Since implemented:** measured peak ~494 MiB (limit 768 MiB) under the k6 load test.
**Strong answer:** Heap ≈ 537 Mi; the rest (≈ 230 Mi) must hold metaspace (Spring/Hibernate ≈
100–150 Mi), thread stacks (Tomcat 200 threads × up to 1 Mi, mostly uncommitted), code cache, direct
buffers (Mongo/Netty). It's in range for this service; verify with NMT
(`-XX:NativeMemoryTracking=summary`, `jcmd <pid> VM.native_memory`) under load, watch for OOMKilled
(container) vs `OutOfMemoryError` (heap). Requests (384 Mi) below limits allow bursting but risk
eviction under node pressure.
**Prove it:** `kubectl top pods -n ticketing`; `kubectl -n ticketing describe pod -l app=ticket-service | grep -A2 Limits`.

### Q10. Why didn't Testcontainers find Docker at first, and what does that teach about build reproducibility? ★★★
**30-second headline:** Docker 29 rejected the old API version so integration tests were skipped, not failed; treat skips as failures and pin the toolchain.
**Weak answer (what fails):** "Tests passed."
**Since implemented:** a CI gate now fails on any skipped test, and the Maven wrapper pins the build tool.
**Strong answer:** Docker Engine 29 rejects the old default API version used by Testcontainers'
bundled docker-java (`/info` returned an empty 400), so Docker looked "unavailable" and the
integration tests were **skipped**, not failed: a silent loss of coverage. Fixed by pinning
`api.version=1.44` in Surefire. Lessons: make skipped critical tests fail CI (or alert on skip counts),
pin toolchains (Maven wrapper, JDK version), and treat the test environment as part of the build.

### Q11. Paging: what's wrong with `OFFSET` paging at scale and what would you do? ★★★★
**30-second headline:** OFFSET scans and discards rows and shifts under inserts; use keyset pagination on (created_at, id) with a matching index.
**Weak answer (what fails):** "Add an index on created_at."
**Strong answer:** `OFFSET n` scans and discards n rows → slow deep pages; concurrent inserts shift
pages (duplicates/missing items). Use keyset/seek pagination: `WHERE (created_at, id) < (:lastCreated,
:lastId) ORDER BY created_at DESC, id` with an index matching it (`ix_ticket_tenant_status` /
`ix_ticket_tenant_owner` lead with tenant). The tie-breaker `id` here already makes ordering
deterministic.

### Q12. Virtual threads (Java 21): would you enable `spring.threads.virtual.enabled` here? ★★★★
**30-second headline:** Helps I/O-bound concurrency, but the connection pool still caps DB work; check pinning and driver support and measure.
**Weak answer (what fails):** "It makes everything faster."
**Strong answer:** The service is I/O-bound (two DBs, JWKS), so virtual threads raise concurrency
without big pools. Watch: pinning on `synchronized` in drivers (JDK 24 removes most pinning), the
JDBC pool still caps DB concurrency (HikariCP size is the real limit), ThreadLocal tenant context
works per virtual thread, and Mongo/JDBC driver compatibility. Measure before/after; it doesn't fix a
saturated database.

### Q13. Rapid fire
* Why are DTOs records? → immutable, concise, no setters for tenant/email to be injected.
* Why does `TicketService` take no tenant parameter? → callers cannot ask for another tenant's data.
* `TICKET_LOCK_TIMEOUT` default? → `PT0S` (locks never expire; takeover opt-in, recorded in history).
* Which exception becomes 409? → `TicketConflictException` (domain or optimistic-lock).
* What validates `size=10000`? → `@Max(200)` with built-in method validation → 400.
