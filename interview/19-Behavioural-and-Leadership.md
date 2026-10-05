# Behavioural and leadership questions (Solution / Enterprise Architect)

Enterprise-architect rounds are often half behavioural: interviewers test judgement, influence and
honesty, not just technology. Answer with **STAR** (Situation, Task, Action, Result) plus **Reflection**
(what you learned / would do differently), in about two minutes, with numbers where you can.

Every model answer below uses something that **actually happened** while building this system, so you can
tell it truthfully and survive deep follow-ups. Replace or extend with your own career stories.

## How you are scored

| Signal | Strong | Weak |
|---|---|---|
| Ownership | "I decided / I was wrong / I fixed" | "we", "they", passive voice for failures |
| Judgement | options, trade-off, decision, evidence | jumping to the answer, or no decision |
| Influence | data, prototypes, risk framed in business terms | authority, persistence, "best practice says" |
| Honesty | admits mistakes and limits early | perfect stories, no failures |
| Learning | concrete change in behaviour or process | "I learned a lot" |

---

### B1. Tell me about a time you influenced a decision without having authority. ★★★★
**30-second headline:** I turned an "accepted risk" debate into a cost comparison: closing the two gaps (secrets in Git, public admin console) took less effort than governing them as exceptions, and showed it with a working implementation.
**Weak answer (what fails):** "I convinced them because I was right" or a story where you simply escalated.
**Model answer (STAR):** *S:* The platform carried two documented accepted gaps: demo passwords in the
repository and the Keycloak admin console reachable through the WAF. *T:* I had no authority to change the
design; the owner had explicitly asked for no design changes without approval. *A:* Instead of arguing
principles I (1) wrote what a proper dispensation would require (owner, expiry, compensating control),
(2) showed the compensating control did not exist yet (no alert on admin-realm login failures), and (3)
presented options with effort and footprint, including a laptop-friendly one. *R:* Approval in one
round; both gaps closed (OpenBao + External Secrets with rotated random credentials; admin endpoints
blocked at WAF and Kong) and the alert proven to fire. *Reflection:* frame risk acceptance as an ongoing
cost, and bring the cheaper path already prototyped.
**Probes:** "What if they had said no?" → document the dispensation properly with an expiry and an
owner, and make the pipeline enforce the expiry trigger.

### B2. Tell me about a decision of yours that turned out to be wrong. ★★★★★
**30-second headline:** I chose two databases for a modest workload; the dual-write window became the biggest correctness risk and needed an outbox to fix. Today I'd start with PostgreSQL + JSONB and add a document store only when the data demanded it.
**Weak answer (what fails):** A "mistake" that is really a strength, or blaming requirements.
**Model answer:** *S:* Workflow state in PostgreSQL, ticket details and history in MongoDB. *T:* Keep
them consistent. *A:* The first version wrote Mongo first and compensated on failure; a later review found
history could be lost silently (a write that matched no document raised no error and logged nothing). I
replaced it with a transactional outbox, idempotent projection, metrics and alerts, and a test that
rebuilds a lost document. *R:* No silent loss, proven by an integration test; but the system carries
complexity that one store would have avoided. *Reflection:* choose the simplest store that meets current
needs and record the decision (ADR) with the trigger for revisiting it.
**Probes:** "Why didn't you migrate to one database then?" → the outbox fixed the risk at a fraction of
the migration cost; an ADR now records when to revisit.

### B3. Tell me about a time you stopped or reversed something you had started. ★★★★
**30-second headline:** I deployed Grafana 13 for observability; it consumed ~8 CPU cores on the laptop during start-up. I rolled back to the 11.x line within the hour and added CPU limits to every observability component.
**Weak answer (what fails):** Defending the original choice or blaming the tool.
**Model answer:** *S:* The owner had asked to protect laptop resources. *T:* Add dashboards without
hurting the developer machine. *A:* Measured (`kubectl top`), saw Grafana 13 at ~7.9 cores and stuck
in start-up for 15 minutes, switched to Grafana 11.6 (LTS line) and capped CPU on all observability pods.
*R:* Node CPU back to ~2 %, Grafana ready in seconds. *Reflection:* "latest" is not a requirement; pin
versions that meet the constraint, and put resource limits on everything by default.

### B4. How did you persuade a senior leader (CTO) to accept a trade-off they didn't like? ★★★★★
**30-second headline:** I used their own goal and numbers: the owner wanted minimal laptop resources but also replication and point-in-time recovery. I showed the conflict explicitly with three options and recommended single instance + PITR, which met both goals.
**Weak answer (what fails):** "I convinced them" without the conflict, the options or the data.
**Model answer:** *S:* Requirements conflicted: "replicated databases" vs "keep a single replica to save
resources". *T:* Avoid silently choosing one. *A:* Stated the conflict, offered (1) single instance + PITR
(same footprint), (2) documentation only, (3) replicas on demand, with costs and what each proves;
recommended (1). *R:* Chosen; PITR proven by a restore drill that excluded data created after the target
time. *Reflection:* make conflicts visible early and let the decision-maker decide with clear options.

### B5. Describe a high-pressure situation where the obvious diagnosis was wrong. ★★★★
**30-second headline:** After a certificate rotation the end-to-end suite failed 24 checks; instead of rolling back, I checked the gateway access log, which showed the requests had succeeded. The test harness was the problem, not the platform.
**Weak answer (what fails):** "I reran it until it passed."
**Model answer:** *S:* Rotation drill, then 24 failures and a red build. *T:* Decide whether the rotation
broke production paths. *A:* Correlated failing checks with Kong's access log (201s), found leftover
port-forwards and a duplicated output line in the harness, fixed the harness, reran three times clean.
*R:* Rotation procedure validated; harness hardened. *Reflection:* evidence before action; flaky tests
are bugs with owners.

### B6. Tell me about a time a test told you something false. ★★★
**30-second headline:** My first load test showed 429 errors; the cause was k6 numbering virtual users across scenarios so two shared one identity and correctly tripped the per-user rate limit. I fixed the test, not the system, and reran: 0 % errors.
**Weak answer (what fails):** Raising the rate limit to make the numbers green.

### B7. How do you handle being told "do not change the design without approval"? ★★★
**30-second headline:** I separate proposals from changes: every design change goes to the owner as options with impact; I only implement after approval, and I keep the current system working while proposing.
**Weak answer (what fails):** Treating it as an obstacle, or quietly "improving" things.

### B8. How do you balance delivery speed with quality? ★★★★
**30-second headline:** Make quality cheap and automatic: tests that cannot be skipped, scans that gate builds, runbooks that are executed rather than written; then speed comes from confidence, not shortcuts.
**Weak answer (what fails):** "Quality always wins" or "we fix it later".
**Model answer:** Example: a dependency scan found 7 critical CVEs; a patch-level upgrade (Spring Boot,
Tomcat, pgjdbc, Jackson) fixed them in one pass, verified by 43 tests and 56 end-to-end checks, and the
pipeline now blocks new critical/high findings.

### B9. How do you explain a technical risk to a non-technical executive? ★★★★
**30-second headline:** Business impact, likelihood, cost to fix, and what happens if we don't, in one page: "a lost approval record means a customer dispute we can't evidence; fixing it costs two weeks; ignoring it costs one incident per quarter."
**Weak answer (what fails):** Explaining the outbox pattern.

### B10. How do you build an architecture practice that teams actually follow? ★★★★★
**30-second headline:** Paved roads and guardrails over review boards: give teams a working, secure default (this platform's patterns as templates), enforce the few non-negotiables in pipelines, and review only deviations.
**Weak answer (what fails):** More mandatory reviews and documents.

### B11. Rapid fire (have a 30-second story ready for each)
* A time you said no to a stakeholder. (→ B7)
* A time you changed your mind because of data. (→ B3, B6)
* Your biggest technical regret. (→ B2)
* A time you simplified something. (→ Kong DB-less, config as code)
* A time you protected a team from scope creep. (→ conflicting requirements, B4)
