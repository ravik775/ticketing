# Audit, breach detection and alerting (no silent slips)

## Concepts you must own

* **Audit trail vs logs:** an audit record answers *who did what, to which resource, when, from
  where, with what outcome (allowed/denied), and why (policy)*. It must be complete, attributable,
  **tamper-evident**, retained, and readable by security but not editable by the people it audits.
* **Detection engineering:** start from attacker behaviour (MITRE ATT&CK techniques, OWASP API
  risks), define the signal that would reveal it, make sure the signal is **captured**, then write the
  alert. "We log it" is not detection.
* **Silent failure ("silent slip"):** a control or a write fails and nothing turns red. Causes:
  swallowed exceptions, WARN-only logs nobody reads, fail-open defaults, skipped tests, ignored return
  values, configuration that is valid but not effective (e.g. a NetworkPolicy without an enforcing CNI).
* **Make failures loud:** metric counters for every degraded path, alerts on them, synthetic probes
  that assert controls still block, **dead-man's switch** alerts that fire when the monitoring itself
  goes quiet, and CI that fails on skipped critical tests.
* **Alert design:** symptom-based paging, severity routing, runbook link, deduplication, ownership.

## Local (narrowed) vs Production (unwrapped)

| Aspect | Local k3d today | Production |
|---|---|---|
| Log storage | pod stdout via `kubectl logs` (lost when a pod is replaced; ~1 h of k8s events) | shipped by Fluent Bit/ADOT to a central store (Loki/OpenSearch/CloudWatch) + SIEM; retention by class (e.g. 90 d technical, 1 y+ security) |
| Tamper evidence | none | write-once storage (S3 Object Lock / WORM), separate security account, hash-chained audit stream |
| Keycloak events | **on** (ticketing + master), shipped to Loki | user + admin events on, shipped to SIEM |
| Kubernetes API audit | **on** (metadata-level policy), shipped to Loki | EKS control-plane audit logs → CloudWatch → SIEM |
| Network flow evidence | none (kube-router does not log denies) | VPC Flow Logs; Cilium Hubble or VPC CNI policy logs |
| Database audit | none | `pgaudit` (DDL, role changes, superuser use), `log_connections`; DocumentDB/Atlas audit logs |
| Alerting | none (humans run `e2e.sh`) | Prometheus/Alertmanager or CloudWatch alarms → on-call (PagerDuty/Opsgenie), SIEM correlation rules |
| Control validation | `scripts/e2e.sh` on demand (53 checks) | the same probes scheduled every few minutes against production (synthetic security canaries) |

## Silent-slip inventory of THIS system (verified in the code and cluster)

| # | Silent slip | Where | Effect | Make it loud |
|---|---|---|---|---|
| S1 | History append failure only logs WARN | `TicketService.record()` | status changed, history missing | counter `ticket_history_write_failures_total` + alert > 0; outbox |
| S2 | `updateFirst` result ignored: if the Mongo document is missing, **no exception and no log** | `DocumentStore.appendEvent()` | history silently dropped | check `getMatchedCount()==0` → error/metric |
| S3 | Compensation failure suppressed | `TicketService.create()` | orphan Mongo document | metric + nightly reconciliation (`docs/03` §3.4) with alert on mismatch |
| S4 | Integration tests **skip** when Docker is unreachable | `@Testcontainers(disabledWithoutDocker = true)` | green build without DB tests (happened once) | CI fails if skipped > 0 for the integration suite |
| S5 | Renewed certificate not loaded until restart | ticket-service, WAF, Kong | outage 30 days later | served-vs-secret serial check (sweep below), expiry alert |
| S6 | WAF left in `DetectionOnly` after an incident | `MODSEC_RULE_ENGINE` | no blocking, still "green" | policy check + alert when engine ≠ On |
| S7 | Rate limiting falls back to client IP when the subject header is absent | Kong `limit_by: header` | per-user fairness silently lost | e2e "same user one quota" as a canary |
| S8 | NetworkPolicies are only effective if the CNI enforces them | k3s kube-router / EKS VPC CNI flag | policies "applied" but not enforced | rogue-pod canary (e2e "blocked by NetworkPolicy") |
| S9 | Missing tenant context fails closed (errors, empty lists) | `TenantContextHolder.require()`, RLS | safe but looks like an outage | count `TenantAccessException`/fail-closed errors, alert on spikes |
| S10 | Keycloak events and K8s audit disabled | realm, k3s | breach leaves no evidence | enable + ship (production table) |

## Prove it (silent-slip sweep, runs against the local cluster)

```bash
export PATH="$HOME/bin:$PATH"
echo "WAF engine: $(kubectl -n edge get deploy waf -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="MODSEC_RULE_ENGINE")].value}')"   # must be On
for c in "edge waf waf-edge-tls" "ticketing ticket-service ticket-service-tls"; do set -- $c
  s=$(kubectl -n $1 get secret $3 -o jsonpath='{.data.tls\.crt}' | base64 -d | openssl x509 -noout -serial)
  kubectl -n $1 port-forward deploy/$2 18990:8443 >/dev/null 2>&1 & pf=$!; sleep 3
  v=$(openssl s_client -connect localhost:18990 -servername ticketing.localtest.me </dev/null 2>/dev/null | openssl x509 -noout -serial); kill $pf; wait $pf 2>/dev/null
  echo "$2: $([ "$s" = "$v" ] && echo 'certificate OK' || echo 'STALE certificate: run scripts/reload-certs.sh')"; done
echo "history write failures (24h): $(kubectl -n ticketing logs deploy/ticket-service --since=24h | grep -c 'could not be written to MongoDB')"
echo "workload-identity rejections (24h): $(kubectl -n ticketing logs deploy/ticket-service --since=24h | grep -c 'untrusted workload identity')"   # must be 0
echo "WAF blocks (1h): $(kubectl -n edge logs deploy/waf --since=1h | grep '"transaction"' | grep -c '"http_code":403')"
KC() { MSYS_NO_PATHCONV=1 kubectl -n auth exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config; }
KC config credentials --server http://localhost:8080/auth --realm master --user admin --password "$(kubectl -n auth get secret keycloak-env -o jsonpath='{.data.KC_BOOTSTRAP_ADMIN_PASSWORD}' | base64 -d)" >/dev/null 2>&1
echo "Keycloak events: $(KC get realms/ticketing --fields eventsEnabled,adminEventsEnabled | tr -d ' \n')"      # false locally (S10)
bash scripts/e2e.sh | tail -1                                                                                   # the control canaries
```

---

## Questions

### Q1. An attacker steals a valid approver token for tenant acme and starts approving tickets. What evidence exists, and what would alert? ★★★★★
**30-second headline:** Attributable actions exist (WAF/Kong lines, ticket history), but per-action context (IP, user agent, session) and behavioural baselines are what detect a stolen token; respond by revoking sessions and reversing decisions.
**Weak answer (what fails):** "The audit log shows who did it" (it shows the account, not the person).
**Strong answer:** Evidence: WAF access line (time, path, status, correlation id), Kong line, and the
ticket history event (`APPROVE` by the approver's email) in Mongo, so the *action* is attributable to
the account, not to the attacker. What's missing: the source context per action (IP, user agent,
session id), and any baseline of "normal" behaviour. Alerts that would fire in production: unusual
volume of decisions per approver (rate vs baseline), decisions outside working hours, same `sub` from
two geographies/ASNs, approvals immediately after a role change. Response: revoke sessions in Keycloak
(refresh fails; access tokens die within 300 s), reverse decisions using history.
**Local:** evidence only in pod logs and Mongo; no alert.
**Production:** structured audit events `{sub, tenant, action, ticketId, outcome, ip, ua, sid,
correlationId}` from the service to the SIEM; UEBA-style rules; session revocation runbook.

### Q2. List the breach scenarios for this system and the exact signal that detects each. ★★★★
**30-second headline:** One signal per scenario: BOLA probing (404 bursts), credential stuffing and admin attacks (LOGIN_ERROR), privilege change (admin events), lateral movement (workload-identity 403), secret theft and exec (Kubernetes audit), web attacks (WAF rules).
**Weak answer (what fails):** "Send logs to a SIEM."
**Since implemented:** most of these detections now run locally (Loki rules → Alertmanager).
**Strong answer:**
| Scenario | Signal | Captured locally? |
|---|---|---|
| Cross-tenant/BOLA probing | many 404s on `/api/**/{id}` per user | partially (WAF/Kong logs) |
| Credential stuffing | Keycloak `LOGIN_ERROR` bursts per IP/user; brute-force lockouts | no (events off) |
| Admin console attack | `LOGIN_ERROR` on realm **master**, admin events | yes (rule fires, verified) |
| Privilege escalation | admin event: user added to `/tenant/approver` (esp. self or off-hours) | no |
| Lateral movement inside cluster | service 403 "untrusted workload identity"; NetworkPolicy denies | first yes (log), second no |
| Rogue certificate | cert-manager `CertificateRequest` for `CN=kong-gateway` outside `gateway` | no (no k8s audit) |
| Secret theft | k8s audit `get/list secrets`, `exec`, `port-forward` | no |
| Data exfiltration | high-volume list calls per user/tenant (page × rate) | partially (rate headers, logs) |
| Web attack campaign | WAF block rate by rule family | yes (audit JSON) |
| DB tampering | `pgaudit` role/DDL changes, superuser sessions | no |
Strong candidates notice that the **accepted gap** (admin console via WAF) *requires* the master-realm
login alert as its compensating control.

### Q3. Turn S1 and S2 (lost history) from silent to loud. Show me the code change and the alert. ★★★★
**30-second headline:** S2: check updateFirst's matched count and throw; S1: count failures as a metric and alert; structurally, the outbox makes the event impossible to lose.
**Weak answer (what fails):** Adding a log.error and calling it done.
**Would I do it again?** Yes; and I'd hunt for every ignored return value in persistence code the same way.
**Since implemented:** implemented on 2026-10-05 (outbox, idempotent apply, failure metrics, OutboxEventsDead/Backlog alerts).
**Strong answer:** S2: check the update result:
`UpdateResult r = mongo.updateFirst(...); if (r.getMatchedCount() == 0) throw new IllegalStateException(...)`.
S1: in `record()`, increment a Micrometer counter
`ticket.history.write.failures{type}` alongside the WARN; expose via Actuator/Prometheus; alert
`increase(ticket_history_write_failures_total[5m]) > 0` (ticket) and page if it persists 15 min.
Structural fix: transactional outbox so the event cannot be lost, plus reconciliation as a safety net.
**Local:** WARN in pod log; check with the sweep above.
**Production:** metric + alert + runbook; outbox lag metric.

### Q4. How do you make the audit trail tamper-evident, and who may read or delete it? ★★★★
**30-second headline:** Ship off-host immediately, write-once storage in a separate account, hash-chaining or signed batches, trusted time, least-privilege access that is itself audited.
**Weak answer (what fails):** "The logs are in Elasticsearch."
**Strong answer:** Ship in near-real time off the host (an attacker with node access can edit local
files); write to append-only storage (S3 Object Lock compliance mode, or a SIEM with immutable
indexes) in a **separate account** with separate admins; hash-chain records (each record includes the
hash of the previous) or sign batches; time from a trusted source; access via least-privilege roles
with access itself audited; retention per policy; legal hold. Ticket history in Mongo is *business*
audit, editable by DB admins, so not sufficient as security evidence on its own.

### Q5. Your alerting pipeline itself fails silently. How would you know? ★★★★
**30-second headline:** A Watchdog alert that always fires to an external heartbeat, plus alerts on absent logs, shipper errors and ingestion lag; test routes monthly.
**Weak answer (what fails):** "We'd notice."
**Since implemented:** the Watchdog alert is implemented and verified firing.
**Strong answer:** Dead-man's switch: an always-firing "Watchdog" alert routed to a heartbeat service;
if the heartbeat stops, the external service pages. Plus: alert on **absence** of logs from each
component (`absent_over_time` / "no logs for 10 min"), on log-shipper errors and back-pressure, and on
SIEM ingestion lag. Test alert routes monthly (fire a synthetic alert end to end).

### Q6. Which alerts page, which ticket, and how do you avoid alert fatigue? ★★★★
**30-second headline:** Page only on confirmed, low-noise security and SLO signals; ticket the noisy ones; dedupe, inhibit, own and review alerts weekly.
**Weak answer (what fails):** Every alert pages.
**Strong answer:** Page: confirmed security signals with low false-positive rate (workload-identity
rejections > 0, master-realm login failures burst, admin role change outside change window, WAF engine
≠ On, certificate < 7 days, SLO fast burn, history-write failures persisting). Ticket: WAF block-rate
spikes (often benign scanners), 404 spikes, slow burns. Fatigue control: dedupe and group by
correlation id/tenant, inhibit downstream alerts when an upstream is down, every alert has an owner and
a runbook, review noisy alerts weekly and delete or tune them.

### Q7. CI shows green but 7 integration tests were skipped. Governance view? ★★★
**30-second headline:** A skipped security test is a failed control: make CI fail on skips and trend test counts.
**Weak answer (what fails):** "Green is green."
**Since implemented:** implemented (scripts/check-test-reports.sh in CI).
**Strong answer:** A skipped security test is a failed control. Policy: the integration stage must run
with Docker available; Surefire/Failsafe report parsed and the build fails if `skipped > 0` for tagged
critical suites; test counts trended over time (a sudden drop = alert). This happened here (Docker
Engine 29 API version), and was found only because someone read the output.
**Local:** `disabledWithoutDocker = true` is convenient for laptops without Docker.
**Production CI:** Docker guaranteed on runners; skip = failure.

### Q8. Prove a control *still works* in production without waiting for an attacker. ★★★★
**30-second headline:** Scheduled synthetic attacks and policy checks against production, exported as metrics, alerting when an expected block turns into a pass.
**Weak answer (what fails):** Annual pen-test only.
**Strong answer:** Synthetic security canaries: scheduled jobs that send a harmless SQLi probe
(expect 403), call an unknown path (404), use an ID token (401), attempt cross-tenant access (404),
start a rogue pod (connection refused), check WAF engine mode, check certificate serials and expiry.
Results exported as metrics; alert when an expectation flips. That is `scripts/e2e.sh` turned into a
CronJob with a dedicated test tenant and test identities (password grant disabled in prod → use a
confidential test client).

### Q9. Rapid fire
* Is ticket history a security audit log? → business audit; not tamper-evident.
* Which local signal already detects lateral movement? → the service's "untrusted workload identity" 403/log.
* Why must the admin console gap have an alert? → it is the compensating control for an accepted risk.
* Silent slip that produces *no* log line at all? → S2 (`updateFirst` result ignored).
* How do you know Alertmanager is alive? → Watchdog alert + external heartbeat.
