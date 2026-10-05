# Observability, SLOs, alerting and incident response

## Concepts you must own

* **Three pillars + events:** logs (discrete facts), metrics (aggregates over time, cheap, alertable),
  traces (causal path and timing across services), events (state changes: deploys, k8s events, audit).
* **Correlation:** a request/trace ID propagated across hops (W3C `traceparent`, or a correlation
  header) and written in every log line.
* **SLI / SLO / SLA / error budget:** SLI = measured ratio (good events / valid events); SLO = target;
  SLA = contractual; error budget = 1 − SLO, spent by incidents and risky changes.
* **Golden signals:** latency, traffic, errors, saturation. RED for request-driven services, USE for resources.
* **Alerting:** alert on symptoms users feel (SLO burn rate), not on every cause; multi-window
  multi-burn-rate alerts; runbooks linked from alerts.
* **OpenTelemetry:** vendor-neutral API/SDK/collector for traces, metrics, logs; sampling (head vs tail).
* **Cardinality:** labels like `user_id` or `ticket_id` in metrics explode cost; keep them in logs/traces.
* **Security & privacy of telemetry:** no tokens/PII in logs, retention policies, access control.

## How this application stands today (be honest about gaps)

| Signal | Available | How |
|---|---|---|
| Access logs | yes | WAF (`id=`, status, upstream, time), Kong (status, `kong_request_id`) |
| Correlation | partial | WAF `$request_id` → `X-Correlation-ID` → Kong echoes to client; **not** in service logs |
| Security events | yes | WAF audit JSON (rule IDs, no headers/bodies) |
| Business audit | yes | ticket `events` history (who/what/when/comment) in Mongo |
| K8s events | yes | `kubectl get events` (1 h retention) |
| Resource metrics | current values | metrics-server → `kubectl top` |
| Metrics history, dashboards, alerts | **yes** | Prometheus (3-day retention), Grafana dashboard, Alertmanager; Kong `prometheus` plugin, Actuator + Micrometer |
| Tracing | **yes** | OpenTelemetry: Kong plugin + Micrometer tracing â Tempo (24 h) |
| Login events | off | Keycloak events disabled by default |

## Local (narrowed) → Production (unwrapped)

> Local design unchanged; production items are **proposals requiring approval**.

| Q | Aspect | Local (narrowed) | Production (unwrapped) | Validate in production |
|---|---|---|---|---|
| Q1 | SLOs | none | availability, latency, login SLOs at the edge | SLO reports, error budget policy |
| Q2 | Tracing | Kong → service traces in Tempo, WAF correlation ID | OpenTelemetry end to end, tail sampling | trace completeness % per request |
| Q3 | Service log correlation | trace ID and correlation ID in every service log line | MDC + structured JSON logs | log query by correlation ID returns all hops |
| Q4 | Dashboards | `kubectl logs/top` | Grafana/CloudWatch dashboards per layer | on-call review of dashboard usefulness |
| Q5 | Alerts | none | burn-rate pages, ticket alerts, runbooks | alert route test monthly |
| Q6 | Investigation | logs + Mongo history | same + traces | incident postmortems |
| Q7 | Logging policy | stdout, no retention | classified fields, retention tiers, access control | log PII scans |
| Q8 | Cardinality | n/a | label policy enforced in review | series count budget alert |
| Q9 | Control observability | `e2e.sh` manual | scheduled canaries exporting metrics | canary failure pages |

## Prove it

```bash
ID=$(curl -sk -D - -o /dev/null https://ticketing.localtest.me:8443/api/me | tr -d '\r' | awk 'tolower($1)=="x-correlation-id:"{print $2}')
echo "correlation id: $ID"; sleep 1
kubectl -n edge logs deploy/waf --since=1m | grep "id=$ID"                 # same ID in the WAF access log
kubectl -n edge logs deploy/waf --since=1h | grep '"transaction"' | grep -oE '"ruleId":"[0-9]+"' | sort | uniq -c | sort -rn | head
kubectl get events -A --sort-by=.lastTimestamp | tail
kubectl top pods -A
```

---

## Questions

### Q1. Define three SLOs for this product, with SLIs precise enough to implement. ★★★★
**30-second headline:** API availability (non-5xx at the gateway, 99.5 % locally), decision latency (99 % < 1 s at the service), login success from Keycloak events; measured at the edge, synthetic traffic excluded.
**Weak answer (what fails):** SLOs on CPU or uptime of pods.
**Since implemented:** availability and latency SLOs with burn-rate alerts are implemented in Prometheus.
**Strong answer:** (1) **API availability**: proportion of `/api/**` requests (excluding 4xx caused
by clients, but **including** 429s if limits are wrong) answered non-5xx, measured at the WAF/Kong:
99.9 % over 28 days. (2) **Decision latency**: 99 % of `POST …/decision` complete < 800 ms at the
edge. (3) **Login success**: proportion of authorization-code exchanges that succeed (Keycloak events
`CODE_TO_TOKEN` vs `CODE_TO_TOKEN_ERROR`) ≥ 99.5 %. Measure at the edge because that's what users
experience; exclude synthetic traffic or tag it.
**Follow-ups / traps:** "Is a 403 an error?" (no for availability; but a spike of 403 after a deploy is
a correctness signal: separate alert.)

### Q2. Design end-to-end tracing for WAF → Kong → service → Postgres/Mongo. What changes in each component? ★★★★★
**30-second headline:** W3C traceparent through WAF → Kong (opentelemetry plugin) → service (Micrometer + OTLP) → Tempo, with trace IDs in logs, tail sampling in production and no PII in span attributes.
**Weak answer (what fails):** "Add a tracing library to the service."
**Would I do it again?** Yes; traces from Kong and the service now land in Tempo, and the trace ID is in every service log line.
**Since implemented:** implemented locally on 2026-10-05.
**Strong answer:** Use W3C `traceparent`. WAF: nginx OpenTelemetry module or at minimum propagate the
incoming header and add `$request_id` as an attribute. Kong: `opentelemetry` plugin (OSS) creates spans
and propagates context. Service: `micrometer-tracing-bridge-otel` + OTLP exporter; Spring auto-instruments
MVC, JDBC (datasource-micrometer), Mongo (observation API); put trace and correlation IDs in log MDC.
Collector: OTel Collector with **tail sampling** (keep errors and slow traces, sample the rest);
backend Tempo/Jaeger/X-Ray. NetworkPolicies must allow egress to the collector; mTLS hop unaffected
(headers inside TLS). Don't put user emails in span attributes (PII); use `sub`.

### Q3. The service logs don't contain the correlation ID. Fix it properly. ★★★
**30-second headline:** Read the gateway's X-Correlation-ID (trusted only because the caller is mTLS-pinned), put it in the MDC, clear it in finally, and include it in error bodies.
**Weak answer (what fails):** Generating a new ID in the service.
**Since implemented:** implemented (cid= in every service log line).
**Strong answer:** A servlet filter (or Micrometer's observation) that reads `X-Correlation-ID`
(trusting it only because the caller is the mTLS-pinned gateway), puts it into SLF4J MDC, and clears it
in `finally`; add `%X{correlationId}` to the log pattern (or JSON logging with
`logging.structured.format.console=ecs`, Boot 3.4+). Echo it in error `ProblemDetail` bodies so users can
quote it.

### Q4. What would be on the on-call dashboard for this system? ★★★★
**30-second headline:** SLO burn first, then RED per hop, saturation (pools, connections, GC), security signals (WAF, 401, workload identity, login failures) and platform health (restarts, certificates, deploy markers).
**Weak answer (what fails):** A wall of CPU graphs.
**Since implemented:** a Grafana dashboard with these panels is provisioned.
**Strong answer:** Top row: SLO burn (availability, latency) per journey. Second: RED per hop (WAF,
Kong routes, service endpoints), 429 rate per tenant, 5xx by upstream. Third: saturation: pod CPU/mem
vs limits, Hikari active/pending, Postgres connections/locks, Mongo ops latency, JVM GC pause. Fourth:
security: WAF blocks by rule family, 401s, `Caller workload identity` 403s (must be 0), Keycloak
`LOGIN_ERROR`. Fifth: platform: pod restarts, certificate days-to-expiry, deploy markers.

### Q5. Which alerts would you page on, and which would you only ticket? ★★★★
**30-second headline:** Page on user-visible, low-noise signals (fast SLO burn, 5xx, certificates < 7 days, DB down, workload-identity rejections); ticket on slow burns and trends; every alert has a runbook.
**Weak answer (what fails):** Paging on every warning.
**Strong answer:** Page: SLO fast burn (e.g. 14.4× over 1 h), all `/api` 5xx at the edge, certificate
expiry < 7 days, database down, workload-identity rejections > 0 (possible lateral movement). Ticket:
slow burn, certificate < 21 days, WAF false-positive spike after a release, disk > 80 %, pod restarts.
Every page links to a runbook (`docs/` sections are the seed of those runbooks).

### Q6. A user says "approvals randomly fail with 409". How do you investigate with today's tooling? ★★★★
**30-second headline:** Correlation ID → WAF line → Kong line → it's a 409; the detail tells which conflict; ticket history shows the concurrent action; fix is UX (refresh after actions or push updates).
**Weak answer (what fails):** Guessing at "database issues".
**Since implemented:** with tracing, the same investigation now starts from the trace in Tempo.
**Strong answer:** Get time and correlation ID → WAF line (status, upstream) → Kong line → it's an
application 409 (`upstream=409`). Two causes: domain conflict ("locked by X") or optimistic lock
("modified by someone else"): the ProblemDetail `detail` tells which. Check ticket history in Mongo for
concurrent `CLAIMED`/`UNLOCKED` events around that time (another approver unlocked or claimed). If
frequent: UX issue (stale list) → refresh after actions, or push updates. Shows reasoning from evidence
rather than guessing.

### Q7. Logs contain personal data (emails). What's your logging policy? ★★★★
**30-second headline:** Classify fields, log pseudonymous IDs, never tokens/passwords/bodies, mask numbers, retention by class, erasure by expiry, access controls on the log store.
**Weak answer (what fails):** "We don't log PII" without checking.
**Strong answer:** Classify fields; prefer pseudonymous IDs (`sub`) in technical logs; never log tokens,
passwords, request bodies; mask mobile numbers; retention 30–90 days for technical logs, longer for
security audit with restricted access; GDPR erasure: logs expire rather than being edited; document in
RoPA. The WAF here already excludes headers and bodies from audit logs.

### Q8. Metric cardinality: a developer adds `tenant_id` and `ticket_id` labels to request metrics. Your view? ★★★★
**30-second headline:** ticket_id is unbounded: reject. tenant is bounded: acceptable for a few key series if tenants are few hundred; otherwise exemplars, top-N recording rules or logs.
**Weak answer (what fails):** "More labels, more insight."
**Strong answer:** `ticket_id` is unbounded → time-series explosion → cost and query failure: reject;
put it in logs/traces. `tenant_id` is bounded but potentially large (thousands of tenants × endpoints ×
status × instance); acceptable for a few key SLIs if tenants are hundreds, otherwise use exemplars,
per-tenant recording rules for top-N tenants, or logs-based metrics.

### Q9. How do you observe security controls actually working (not just existing)? ★★★★
**30-second headline:** Synthetic canaries that expect blocks (SQLi 403, ID token 401, cross-tenant 404, rogue pod refused) exported as metrics, with alerts when an expectation flips.
**Weak answer (what fails):** "We have a WAF, so attacks are blocked."
**Since implemented:** scripts/e2e.sh and verify-observability.sh provide these checks; the next step is running them on a schedule.
**Strong answer:** Continuous control validation: run `scripts/e2e.sh`-style probes on a schedule
(synthetic attacks expecting 403/404/421; rogue-pod NetworkPolicy checks; mTLS-without-cert check) and
alert when an expectation flips. Plus metrics for denies at each layer. This turns "we have a WAF" into
"we know the WAF blocked SQLi in the last 5 minutes".

### Q10. Rapid fire
* Where does `X-Correlation-ID` originate? → WAF `$request_id`.
* How long are Kubernetes events kept? → ~1 hour by default.
* Which component logs every request with upstream status? → the WAF.
* Why not log `Authorization` at the WAF? → bearer tokens are credentials.
* What turns Keycloak login events on? → Realm settings → Events → Save events.
