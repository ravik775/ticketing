# Maintainability: modularity, technical debt, testing, delivery, operability

## Concepts you must own

* **Modularity:** high cohesion, low coupling, explicit interfaces, information hiding; enforced
  boundaries (JPMS, ArchUnit, separate deployables) beat conventions.
* **Domain model integrity:** invariants live in one place (rich domain objects / aggregates), not
  scattered in controllers.
* **Technical debt:** deliberate vs accidental, prudent vs reckless (Fowler's quadrant); tracked in a
  register with interest (cost of delay) and principal (cost to fix).
* **Testing strategy:** pyramid/trophy: fast unit tests, slice tests, integration with real
  dependencies (Testcontainers), contract tests, end-to-end; plus non-functional (load, security,
  chaos). Tests as executable documentation and as **fitness functions**.
* **Continuous delivery:** trunk-based development, CI on every change, immutable artefacts,
  environment parity, automated rollback, DORA metrics.
* **Reproducibility:** pinned toolchains (Maven wrapper, JDK), pinned images, infrastructure as code.
* **Operability:** runbooks, safe scripts (idempotent, fail loudly), self-describing errors, docs as code.
* **Evolvability:** expand/contract migrations, versioned APIs, feature flags, strangler fig.

## How this application stands

| Aspect | Strength | Debt / gap |
|---|---|---|
| Modules | 3 JPMS modules; API cannot compile against persistence | runtime classpath (compile-time guarantee only) |
| Domain | all lock/approval rules in `TicketWorkflow` (+ DB constraints) | Mongo history writes are outside the transaction (consistency debt) |
| Tests | 43 Java tests (unit, slice, Testcontainers) + 56 e2e checks + k6 load test; CI with no-skip gate | no contract/mutation tests; CI not yet run on GitHub |
| Config | env-overridable YAML, profiles, kustomize | `disableNameSuffixHash` means config changes don't roll pods automatically |
| Scripts | idempotent `up.sh`, `render-kong.sh`, `reload-certs.sh`, `e2e.sh` | Windows/Git-Bash quirks (path conversion, CRLF: fixed with `.gitattributes`); `kill ${PF_PID:-0}` in traps can signal the whole process group |
| Docs | 9 operator guides with verified commands, this interview pack | ADRs not extracted |
| Toolchain | Spring Boot 3.5 parent, `--release 21` | no Maven wrapper; local JDK 26 differs from runtime 21 |
| Certificates | automated issuance/renewal | reload is manual (`reload-certs.sh`) |

## Local (narrowed) → Production (unwrapped)

> Local design unchanged; production items are **proposals requiring approval**.

| Q | Aspect | Local (narrowed) | Production (unwrapped) | Validate in production |
|---|---|---|---|---|
| Q1 | Domain rules | in `TicketWorkflow` | same + ArchUnit | build gate |
| Q2 | Tests | manual runs | CI with all layers + load/DAST/mutation | pipeline evidence |
| Q3 | Flaky tests | investigated by hand | quarantine policy, flake-rate metric | flake rate trend |
| Q4 | Risky code | reviewed ad hoc | code owners + mandatory security review | review records |
| Q5 | Adding a role | manual steps | checklist template + tests | change PR template |
| Q6 | CI/CD | none in repo | GitHub Actions + GitOps (Argo CD) | DORA metrics |
| Q7 | Scripts | bash for Git Bash/WSL | ShellCheck, or replaced by typed tooling/GitOps | lint gate |
| Q8 | Docs | verified by hand on the laptop | docs-as-code with executed runbook checks | doc test job |
| Q9 | Upgrades | manual | upgrade train + staging rehearsal | upgrade success rate |
| Q10 | Debt register | gap list in `docs/08` | funded debt backlog with interest estimates | debt burn-down |

## Prove it

```bash
MVN=./mvnw   # Maven wrapper in the repository root (pins Maven 3.9.16)
"$MVN" -B verify | grep -E "Tests run:|BUILD"
bash scripts/e2e.sh | tail -1
git log --oneline | head                                    # history of changes
grep -rn "TODO\|FIXME" --include=*.java . | wc -l           # explicit debt markers in code
for f in scripts/*.sh; do bash -n "$f" && echo "syntax ok: $f"; done
git ls-files --eol scripts/*.sh | awk '{print $2, $NF}'     # w/lf: scripts keep LF line endings
```

---

## Questions

### Q1. Where do business rules live, and how do you stop them from leaking into controllers over time? ★★★★
**30-second headline:** Transitions live on the TicketWorkflow aggregate, backed by DB constraints; services orchestrate, controllers map HTTP; JPMS and ArchUnit keep it that way.
**Weak answer (what fails):** "In the service layer" without invariants or enforcement.
**Strong answer:** State transitions and invariants are methods on `TicketWorkflow` (`claim`,
`unlock`, `decide`, `respond`), backed by DB constraints. `TicketService` orchestrates (security
context, persistence, history) but doesn't decide transitions. Controllers only map HTTP. Guarding it:
JPMS (controllers can't see the entity), ArchUnit rules (controllers may depend only on
`com.ticketing.core`), unit tests on the domain model without Spring, review checklists.

### Q2. Rate the testing strategy. What would you add first and why? ★★★★
**30-second headline:** Strong layers (domain, security slice, Testcontainers, e2e); first add CI with a no-skip gate, then load tests, contract tests, mutation testing and DAST.
**Weak answer (what fails):** "Increase coverage."
**Since implemented:** CI with the no-skip gate and a k6 load test now exist.
**Strong answer:** Strong: domain unit tests, security slice tests with real tenant resolution,
Testcontainers integration including RLS and DB constraints, a live e2e suite covering security
controls. Gaps in priority: (1) **CI pipeline** running all of it on every PR (today it's manual).
(2) Fail on **skipped** integration tests (Docker issues silently skipped 7 tests once). (3) Load test
(k6) with SLO assertions. (4) Contract tests between UI/API (or OpenAPI validation). (5) Mutation
testing (PIT) on security-critical classes (`TenantResolver`, `TicketWorkflow`). (6) DAST (OWASP ZAP)
against the WAF-protected stack.

### Q3. The e2e suite failed once with 24 failures after a certificate rotation, then passed three times. How do you handle flaky tests? ★★★★
**30-second headline:** Never rerun-until-green: find the root cause (here the harness, proven by Kong's logs), quarantine with a ticket, make tests hermetic, track flake rate.
**Weak answer (what fails):** Retrying in CI until it passes.
**Strong answer:** Never "rerun until green" silently. Root-cause analysis: Kong's access log showed
the requests actually succeeded (201), so the failure was in the harness (state from concurrent
port-forwards during rotation), not the product; the network check also once captured duplicated
output (fixed with `head -1`). Policy: quarantine with a ticket, fix the harness, make tests hermetic
(unique ports, cleanup traps), record flake rate as a quality metric.

### Q4. What are the riskiest parts of this codebase to change, and how do you make them safer? ★★★★
**30-second headline:** Tenant resolution, transaction boundaries around set_config, string-templated gateway config and WAF rules; protect with mutation tests, store-level integration tests, generated/validated config and route-level e2e.
**Weak answer (what fails):** "Everything is equally risky."
**Strong answer:** (1) `TenantContextFilter`/`TenantResolver`: a bug leaks tenants → mutation testing,
mandatory security review, property-based tests on group parsing. (2) `WorkflowStore.bindTenant` +
transaction boundaries: a moved `@Transactional` breaks RLS binding (fails closed, but breaks the
app) → integration tests per store method. (3) `render-kong.sh` (string-templated YAML; one indent bug
already crashed Kong) → generate with a YAML tool/decK and validate (`kong config parse`) in CI.
(4) WAF rules: allow-list coupling → e2e coverage of every route.

### Q5. A new developer must add a third role "auditor" (read-only, all tickets of a tenant). List every place that changes. ★★★★
**30-second headline:** Keycloak groups, Role enum, controller and service checks, UI, scripts, tests, docs; RLS, Kong and the WAF stay unchanged because policy lives in the token and the service.
**Weak answer (what fails):** Changing the database schema for a role.
**Strong answer:** Keycloak groups `/tenant/auditor` (realm JSON + docs); `Role` enum; `TenantResolver`
(parses it automatically via `Role.parse`); a new controller or endpoints with `@PreAuthorize("hasRole('AUDITOR')")`;
`TicketService` read methods with `require(AUDITOR)`; UI rendering; `add-role.sh` validation list;
e2e + slice tests; docs guide 1. Good answer also notes what **doesn't** change: RLS (tenant-scoped,
role-agnostic), Kong, WAF (paths under `/api/` already allowed). This tests understanding of where
policy lives.

### Q6. How would you set up CI/CD for this repository? ★★★★
**30-second headline:** Wrapper-pinned build, all tests with a no-skip gate, scans, SBOM and signing, ephemeral k3d e2e, promotion by digest with an approval gate, secrets from a manager.
**Weak answer (what fails):** "Jenkins job that runs mvn package."
**Since implemented:** implemented as .github/workflows/ci.yml and release.yml.
**Strong answer:** GitHub Actions: build with Maven wrapper + JDK 21 (matching runtime), `mvn verify`
with Testcontainers (fail on skipped), SBOM + dependency and image scanning, build images tagged by git
SHA, push to registry, sign (cosign). CD: GitOps repo/overlay per environment (kustomize), Argo CD
sync; ephemeral k3d environment in CI running `up.sh` + `e2e.sh` for PRs touching k8s/scripts; promote
by digest; secrets from a secret manager, never from the repo (ends the accepted gap outside the laptop).

### Q7. Shell scripts are part of operations here. What standards would you impose? ★★★★
**30-second headline:** set -euo pipefail, idempotency, prerequisite checks, kill only what you started, no secrets in arguments, ShellCheck, LF endings, or replace with typed tooling.
**Weak answer (what fails):** "Scripts are fine as they are."
**Strong answer:** `set -euo pipefail`; quote everything; idempotency; explicit prerequisites check;
traps that only kill what they started (`[ -n "$PID" ] && kill "$PID"`, never `kill 0`); no secrets
in arguments (process list/shell history: the Google secret ended up in a transcript); ShellCheck in
CI; LF line endings (`.gitattributes`); cross-platform notes (Git Bash path conversion: export
`MSYS_NO_PATHCONV` only per command); or replace with a typed tool (Go/Python, Taskfile) once scripts
grow.

### Q8. Documentation as code: how do you keep `docs/` correct as the system changes? ★★★★
**30-second headline:** Docs change in the same PR, runbook commands are executed in CI, owners are named, reviews are scheduled, and reference sections are generated.
**Weak answer (what fails):** "We have a wiki."
**Strong answer:** Docs live with the code and change in the same PR; every runbook command is
executable and ideally executed in CI (doc tests / "runbook smoke tests"); ownership in CODEOWNERS;
quarterly review; link runbooks from alerts; generate reference parts (API from OpenAPI, inventory from
manifests). The docs here were written by running every command against the live cluster.

### Q9. Dependency and platform upgrades: describe a safe upgrade of Keycloak 26.3 → 27. ★★★★
**30-second headline:** Read migration notes, back up the database, rehearse on a production-like copy, run e2e including token claims and brokering, re-render the gateway key if needed, keep a DB-restore rollback.
**Weak answer (what fails):** In-place upgrade of production.
**Strong answer:** Read migration notes (DB schema migration, theme/SPI changes, hostname options,
default client scopes; e.g. the `basic` scope change in 25 that removed `sub` for clients without it);
back up the Keycloak DB; upgrade a staging environment from a production-like DB copy; run e2e (login,
token claims `typ/azp/groups/sub`, Google brokering, admin automation); verify Kong JWT key unchanged or
re-render; roll out with rollback plan (DB restore, since Keycloak migrations are forward-only).

### Q10. Technical debt register: give me the top five items for this system with interest and principal. ★★★★★
**30-second headline:** Dual-write consistency, manual certificate reload, no CI, single replicas/no backups, email as identity key; prioritised by interest × probability with estimates.
**Weak answer (what fails):** Listing "refactor X" without cost or interest.
**Would I do it again?** Three of the five were paid off this round (outbox, CI, backups/PITR); certificate reload and email identity remain.
**Since implemented:** status changed on 2026-10-05.
**Strong answer:** (1) Dual-write consistency: interest: silent history loss, support time;
principal: outbox (~1–2 weeks). (2) Manual certificate reload: interest: outage risk every 60 days;
principal: Spring reload + Kong secret-based certs (~3 days). (3) No CI: interest: regressions,
manual effort; principal: ~1 week. (4) Single replicas/no backups: interest: downtime/data loss;
principal: platform work (weeks, cost). (5) Email as identity key: interest: ownership errors on
email change; principal: migration to `sub` (~1 week + data migration). Prioritise by interest × probability.

### Q11. Rapid fire
* Where is the lock rule implemented? → `TicketWorkflow.claim/unlock/decide` + DB `CHECK`s.
* Why records for DTOs? → immutability, no setters for tenant/email.
* What makes a WAF rule change take effect? → bump `ticketing/config-version`, apply.
* What keeps bash scripts runnable after a Windows checkout? → `.gitattributes` `eol=lf`.
* How many automated live checks exist? → 53 in `scripts/e2e.sh`.
