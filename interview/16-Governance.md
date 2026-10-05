# Governance: enterprise architecture, standards, risk, compliance, FinOps

## Concepts you must own

* **TOGAF ADM:** Preliminary, A Vision, B Business, C Information Systems (Data + Application),
  D Technology, E Opportunities & Solutions, F Migration Planning, G Implementation Governance,
  H Change Management, with Requirements Management at the centre. **Architecture Contracts**,
  **Compliance Reviews**, **Dispensations** (time-boxed exceptions).
* **Architecture principles:** name, statement, rationale, implications (e.g. "Identity is the
  perimeter", "Data is classified and owned", "Platforms over per-project infrastructure").
* **Building blocks:** Architecture Building Blocks (what: e.g. "Identity Provider") vs Solution
  Building Blocks (how: Keycloak 26).
* **Decision records:** ADRs for traceability; architecture repository; technology radar
  (adopt/trial/assess/hold).
* **Risk management:** identify, assess (likelihood × impact), treat (mitigate, transfer, avoid,
  **accept**), monitor; risk acceptance needs an owner, scope, compensating controls and an expiry.
* **Compliance frameworks:** GDPR (lawful basis, minimisation, erasure, RoPA, DPIA), ISO 27001 /
  SOC 2 controls, data residency.
* **Governance styles:** gatekeeping boards vs **guardrails** (paved roads + policy-as-code + fitness
  functions); Team Topologies (platform, stream-aligned, enabling teams).
* **FinOps:** visibility, allocation (cost per tenant/product), optimisation, unit economics.
* **Open-source licensing:** permissive (Apache 2.0, PostgreSQL License) vs copyleft (GPL/AGPL) vs
  source-available (SSPL, BSL).

## How this application maps to governance artefacts

| Artefact | In this repository | Gap / next step |
|---|---|---|
| Principles applied | identity-centric security, defence in depth, least privilege, config as code | write them down as named principles |
| Decision records | rationale spread across README, `docs/`, code comments | extract numbered ADRs (Architecture Q8) |
| Risk register with acceptance | `docs/08-security-zero-trust.md` §8.8 (Status: **Accepted gap** / open, dated 2026-10-05) | add owner, expiry trigger, review date per row |
| Compliance evidence | `./mvnw verify` (43 tests, 0 skipped), `scripts/e2e.sh` (56 live checks), `verify-observability.sh` (15) | CI runs them on every push (`.github/workflows/ci.yml`); reports kept as artifacts |
| Operational standards | runbooks in `docs/01–09` | link from alerts, review quarterly |
| Supply-chain standard | WAF image pinned by digest | extend to all images; SBOM |
| Data classification | PII: email, mobile; business: ticket content | formal classification + retention policy |

## Local (narrowed) → Production (unwrapped)

> Local design unchanged; production items are **proposals requiring approval**.

| Q | Aspect | Local (narrowed) | Production (unwrapped) | Validate in production |
|---|---|---|---|---|
| Q1 | ADM phase G | informal | architecture contract, compliance reviews at gates | review records per release |
| Q2 | Dispensations | 2 former accepted gaps, both closed 2026-10-05 | none allowed for those two; any new dispensation has owner + expiry | dispensation register with no expired items |
| Q3 | Licences | community editions | legal-approved licence list; Atlas/Enterprise where needed | SBOM licence scan |
| Q4 | Guardrails | manual scripts | paved road + policy-as-code + CI gates | % workloads compliant |
| Q5 | IdP choice | self-hosted Keycloak | ADR for enterprise IdP vs Keycloak | migration rehearsal |
| Q6 | Data governance | none formal | owners, classification, retention, DPIA, RoPA | annual data audit |
| Q7 | API governance | no OpenAPI | contract-first, versioning, inventory | API catalogue = gateway routes |
| Q8 | KPIs | none | DORA, dispensation age, findings SLA, cost/tenant | quarterly governance report |
| Q9 | Build vs buy | reference build | portfolio decision via ARB | business case review |
| Q10 | Lifecycle | manual versions | tech radar, EOL tracking, upgrade train | no component past EOL |

### Where governance is imposed (enforcement points)

| Point | Local today | Production |
|---|---|---|
| Design time | docs + this review | ARB for novel decisions, ADRs, threat models |
| Code | JPMS boundary, tests | + ArchUnit, code owners, mandatory review on security code |
| Build/CI | manual `mvn verify` | pipeline gates: tests (no skips), SBOM, vuln/licence/secret scans, image signing |
| Deploy/admission | `kubectl apply` by an admin | GitOps + admission policies (Kyverno/Gatekeeper: non-root, digests, NetworkPolicy present) |
| Runtime | WAF, Kong, service, RLS, NetworkPolicies | same + AWS WAF, SCPs/IAM boundaries, config rules |
| Operations | runbooks in `docs/` | change management, access reviews, audit, SLO reviews |
| AI (if introduced) | n/a | see 15-AI-Governance-and-Observability (AI gateway, eval gates, use-case register) |

## Prove it

```bash
grep -n "Accepted gap" docs/08-security-zero-trust.md                        # recorded risk acceptances
bash scripts/e2e.sh | tail -1                                                 # control evidence
grep -rn "image:" k8s/*.yaml | sed 's/^.*image: //' | sort -u                 # inventory of third-party components (only the WAF is digest-pinned)
grep -n "<version>\|<parent>" -A2 pom.xml | head                              # platform version baseline (Spring Boot 3.5)
```

---

## Questions

### Q1. Where would this system enter the TOGAF ADM, and what does Phase G look like for it? ★★★★
**30-second headline:** Enters at Phases E/F consuming Phase D building blocks; Phase G is an architecture contract, compliance reviews with test evidence, and time-boxed dispensations.
**Weak answer (what fails):** Reciting the ADM wheel.
**Strong answer:** As a solution delivered within an existing enterprise landscape, it enters at
Phase E/F (opportunities, migration planning) using platform building blocks defined in Phase D
(identity, gateway, PKI, Kubernetes). Phase G: an **architecture contract** with the delivery team
(principles, standards, required evidence), **compliance reviews** at milestones using the e2e suite
and test reports as evidence, and **dispensations** for deviations: exactly what the two accepted
gaps are, with expiry conditions. Phase H handles change requests (e.g. move to AWS, add a region).

### Q2. Turn the two accepted gaps into proper dispensations. What fields and what governance? ★★★★
**30-second headline:** A dispensation names the breached principle, scope, compensating controls, risk owner, approver, expiry trigger and remediation plan, and is tracked and enforced by the pipeline.
**Weak answer (what fails):** "We documented it in the README."
**Would I do it again?** In hindsight, closing both gaps was cheaper than governing them; they are now closed.
**Since implemented:** both gaps were closed on 2026-10-05.
**Strong answer:** For each: ID, principle/standard breached ("Secrets are never stored in source
control"; "Administrative interfaces are not exposed on application ingress"), scope (laptop demo
cluster only), justification, compensating controls (synthetic data, no external reachability, WAF
inspection, brute-force protection), residual risk rating, **risk owner** (accountable executive, not
the engineer), approval (security architect / ARB), **expiry trigger** (any shared, CI or internet-
facing environment → gap must close), review date, remediation plan and cost. Track in the risk
register; the build/deploy pipeline should *enforce* the trigger (e.g. secret scanning blocks
non-demo branches).

### Q3. Kong OSS, Keycloak, ModSecurity, CRS, PostgreSQL, MongoDB. Review the licences and the enterprise risk. ★★★★
**30-second headline:** Kong OSS, Keycloak, ModSecurity, CRS, OpenBao: permissive; PostgreSQL: permissive; MongoDB Community: SSPL needs legal review; Vault moved to BSL, which is why OpenBao (MPL-2.0) was chosen.
**Weak answer (what fails):** "It's all open source."
**Since implemented:** the secrets manager choice (OpenBao vs Vault) was itself a licence decision.
**Strong answer:** Kong OSS, Keycloak, ModSecurity v3, OWASP CRS: Apache 2.0 (permissive). PostgreSQL:
PostgreSQL License (permissive). **MongoDB Community: SSPL**: fine for internal use, but offering the
database itself as a service triggers source-disclosure obligations, and many OSI/enterprise policies
treat SSPL as non-open-source; procurement may require MongoDB Atlas/Enterprise or DocumentDB.
Kong's richer features (OIDC plugin, sliding-window rate limits) need Kong Enterprise: a commercial
dependency decision. Strong candidates connect licensing to the "two databases" ADR.

### Q4. Gatekeeping ARB vs guardrails: how would you govern 30 teams building systems like this? ★★★★
**30-second headline:** A paved road (platform products with secure defaults) plus guardrails as code (admission policies, CI gates, fitness functions); the ARB reviews deviations only, measured by lead time and incidents.
**Weak answer (what fails):** More review meetings.
**Since implemented:** CI gates and SHA-pinned actions now exist for this repository.
**Strong answer:** Paved road: a platform team provides the WAF/gateway/IdP/PKI/Kubernetes baseline
as products with secure defaults; teams consume it via templates. Guardrails as code: Kyverno/Gatekeeper
(non-root, no privileged, digest-pinned images, required NetworkPolicies), CI gates (tests, SBOM,
vulnerability thresholds, secret scanning), fitness functions (e.g. e2e security probes). The ARB
reviews only **deviations** and novel decisions (ADRs), not every design. Measure governance by lead
time and incident rates, not by meetings held.

### Q5. A team wants to replace Keycloak with a SaaS IdP (Auth0, Entra ID). How do you evaluate it? ★★★★
**30-second headline:** Score protocol fit, multi-tenancy features, residency, SLA, cost per MAU, lock-in and exit plan, certifications and integration effort; write an ADR with a reversible migration.
**Weak answer (what fails):** Choosing on brand.
**Strong answer:** Criteria: protocol fit (OIDC, PKCE, group/claim mapping for `/tenant/role` or an
alternative tenant model), multi-tenancy/B2B features (organisations), data residency, SLA vs
self-hosted HA cost, pricing per MAU at target scale, lock-in and exit plan (user export, password hash
portability), security certifications, admin delegation, integration effort (issuer/JWKS change in
Kong and service, token format). Decision as an ADR with a reversible migration path (run both issuers
during transition: Spring Security Q7).

### Q6. Data governance for this system: what must be defined before go-live? ★★★★
**30-second headline:** Owners, classification, lawful basis, retention, residency, erasure across all stores, access model, DPIA and RoPA, encryption requirements.
**Weak answer (what fails):** "GDPR is legal's job."
**Strong answer:** Data owner per entity (tenant, ticket, user identity), classification (PII: email,
mobile; possibly special categories inside free-text descriptions), lawful basis and purpose, retention
(tickets, history, logs, backups), residency, erasure procedure across both stores + Keycloak + logs +
backups (Data Q8), access model (approvers see mobile numbers: justified?), DPIA if large-scale, RoPA
entry, encryption requirements.

### Q7. How do you govern API change and versioning for this API? ★★★★
**30-second headline:** Contract-first OpenAPI, additive changes within a major version, deprecation policy with headers and dates, consumer contract tests, an inventory generated from gateway routes.
**Weak answer (what fails):** Versioning by URL without a deprecation policy.
**Strong answer:** Contract-first (OpenAPI) in the repo, reviewed like code; semantic versioning;
additive changes only within a major version (new optional fields; `page`/`size` were added as optional
with defaults: non-breaking); deprecation policy with `Deprecation`/`Sunset` headers and dates;
consumer-driven contract tests; an API inventory (OWASP API9) generated from gateway routes. Breaking
changes → `/api/v2` route in Kong in parallel.

### Q8. What KPIs show that architecture governance is adding value? ★★★★
**30-second headline:** DORA metrics, paved-road adoption, dispensation count and age, finding fix times, cost per tenant, SLO attainment.
**Weak answer (what fails):** Counting ADRs or meetings.
**Strong answer:** DORA metrics (deployment frequency, lead time, change failure rate, MTTR); % of
services on the paved road; number/age of open dispensations (accepted gaps must not live forever);
security findings by severity and time-to-fix; cost per tenant trend; SLO attainment. Avoid vanity
metrics (number of ADRs, diagrams).

### Q9. Build vs buy: would you buy a ticketing product instead of building this? ★★★★
**30-second headline:** Buy if ticketing isn't differentiating; build when workflow, tenancy or integration is core IP or SaaS can't meet isolation/residency; this build earns its keep as a reference architecture.
**Weak answer (what fails):** Build because "we can".
**Strong answer:** If ticketing is not a differentiating capability, buy (ServiceNow, Jira Service
Management): faster, supported, audited. Build when the workflow/tenancy/integration is core IP, when
SaaS can't meet residency/isolation, or cost at scale favours it. This design's value is as a
**reference architecture** for the platform patterns (Zero Trust, tenancy, identity) that other built
systems will reuse: that's a legitimate EA reason to build.

### Q10. Technology lifecycle: Spring Boot 3.5, Keycloak 26, Kong 3.9, k3s 1.35. How do you keep this current? ★★★★
**30-second headline:** Tech radar, EOL tracking, Dependabot PRs gated by the full test and e2e suite, a quarterly upgrade train, runbooks, no component past EOL without a dispensation.
**Weak answer (what fails):** "Upgrade when something breaks."
**Since implemented:** Dependabot and a CI gate are configured; the CVE scan already forced a Spring Boot/Tomcat patch upgrade.
**Strong answer:** Tech radar + support-window tracking (EOL dates), Renovate PRs with the full test
suite + e2e as the gate, quarterly platform upgrade train, upgrade runbooks (Keycloak DB migrations,
Kong config format, CRS false-positive tuning), and a policy that no component runs past vendor EOL
without a dispensation. Cost of not upgrading is security debt with compounding interest.

### Q11. Rapid fire
* Who owns an accepted risk? → a named business/security risk owner, not the developer.
* ABB vs SBB here? → "Identity Provider" vs "Keycloak 26.3 realm `ticketing`".
* Licence that needs legal review? → MongoDB SSPL.
* What evidence proves tenant isolation to an auditor? → RLS integration test + e2e isolation checks + code review of `TenantResolver`.
* Where are the dispensations recorded today? → `docs/08` §8.8 (Accepted gap rows).
