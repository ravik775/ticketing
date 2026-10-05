# 2. Logs, metrics, traces and events

"Observability" means being able to answer *what is happening* and *what happened* without guessing.
This guide shows what is available in this installation today, exactly how to use it, and what to
add when the platform grows.

## 2.1 What exists today (and what does not)

| Signal | Available? | Where |
|---|---|---|
| **Logs** – one line per request / error | Yes | each pod's output: `kubectl logs` |
| **Correlation ID** – one ID that follows a request | Yes | `X-Correlation-ID` response header = `id=` in the WAF log |
| **Security events** – blocked attacks | Yes | WAF audit log (JSON lines in the WAF log) |
| **Kubernetes events** – restarts, scheduling, probe failures | Yes | `kubectl get events` |
| **Certificate events** – issued/renewed | Yes | `kubectl describe certificate` |
| **Business events** – ticket history (created, claimed, approved …) | Yes | the app UI, the API, MongoDB |
| **Login events** – sign-ins, failed passwords | Off by default | enable in Keycloak (section 2.6) |
| **Resource metrics** – CPU / memory per pod | Yes (current values only) | `kubectl top` |
| **Metrics history / dashboards / alerts** | **No** | add Prometheus + Grafana (section 2.7) |
| **Distributed traces** (timing of each hop) | **No** | add OpenTelemetry (section 2.7) |

## 2.2 Logs

Every component writes its log to its own output; Kubernetes keeps it. The general form is:

```bash
kubectl -n <namespace> logs deploy/<name>              # whole log of the current pod
kubectl -n <namespace> logs deploy/<name> --tail=50    # last 50 lines
kubectl -n <namespace> logs deploy/<name> --since=15m  # last 15 minutes
kubectl -n <namespace> logs deploy/<name> -f           # follow live (stop with Ctrl+C)
kubectl -n <namespace> logs deploy/<name> --previous   # the log of the copy that crashed before the current one
```

| Component | Command | What a line tells you |
|---|---|---|
| WAF (entry point) | `kubectl -n edge logs deploy/waf` | every request: time, client, **id**, host, path, status, upstream status, duration; plus JSON audit records for blocked/suspicious requests |
| Kong (gateway) | `kubectl -n gateway logs deploy/kong` | every request Kong forwarded: path, status, `kong_request_id` |
| ticket-service (API) | `kubectl -n ticketing logs deploy/ticket-service` | start-up, warnings and errors (not every request) |
| Keycloak (login) | `kubectl -n auth logs deploy/keycloak` | start-up, errors, warnings (e.g. failed Google login) |
| UI (nginx) | `kubectl -n ticketing logs deploy/ui` | static file requests |
| PostgreSQL | `kubectl -n ticketing logs postgres-0` | database errors, slow start |
| MongoDB | `kubectl -n ticketing logs mongo-0` | JSON lines; connection and error messages |

Useful filters (Git Bash has `grep`):

```bash
kubectl -n edge logs deploy/waf --since=1h | grep -v '"transaction"' | grep 'status=5'   # server errors seen at the edge
kubectl -n edge logs deploy/waf --since=1h | grep -c 'status=429'                        # how many requests were rate-limited
kubectl -n ticketing logs deploy/ticket-service --since=1h | grep -E 'WARN|ERROR'        # problems inside the API
```

A WAF access line looks like this (one line, wrapped here):

```
2026-10-05T06:12:52+00:00 client=10.42.0.1 id=ded2f41f58bb06bb195ec66175c91253 host=ticketing.localtest.me
  "GET /api/me HTTP/1.1" status=401 bytes=26 upstream=401 time=0.012 ua="curl/8.19.0"
```

* `status` is what the user received; `upstream` is what Kong answered. If `status=403` and
  `upstream=-`, **the WAF** blocked the request; if both are 403, the application refused it.
* `time` is the total seconds the request took (a quick way to spot slowness).

> The `client` address is the address of the local load balancer, not the real user: the k3d load
> balancer on a laptop hides the original address. In AWS (guide 7) the real address is kept.

## 2.3 Following one request (correlation ID)

The WAF gives every request a unique ID, forwards it to Kong in the `X-Correlation-ID` header, and Kong
returns it to the caller for every `/api` call. So when a user reports a problem:

1. Ask for the ID: in the browser press **F12** → **Network** tab → click the failing request →
   **Headers** → *Response headers* → `X-Correlation-ID` (and the time it happened).
2. Find it at the edge:

   ```bash
   kubectl -n edge logs deploy/waf --since=24h | grep 'ded2f41f58bb06bb195ec66175c91253'
   ```

   You see the status, the duration, and, if the WAF blocked it, a JSON audit record with the rule.
3. Find the same request in Kong by time and path (Kong logs its own `kong_request_id`):

   ```bash
   kubectl -n gateway logs deploy/kong --since=24h | grep '05/Oct/2026:06:12:52' | grep '/api/me'
   ```

4. If the status was 5xx, look for the error in the API log around that time:

   ```bash
   kubectl -n ticketing logs deploy/ticket-service --since=24h | grep -E 'WARN|ERROR'
   ```

Without a correlation ID, the API's error message itself is the best clue: every API error is a JSON
body with a human-readable `detail`, e.g. `"Ticket is locked by bob@ticketing.test"`.

## 2.4 Security events (WAF audit log)

When the WAF blocks or flags a request it writes one JSON record (lines containing `"transaction"`).
For privacy it records **no headers and no bodies** (they would contain passwords and tokens), only the
client, the request line and the rules that matched.

```bash
# number of security records in the last hour
kubectl -n edge logs deploy/waf --since=1h | grep -c '"transaction"'

# what was blocked: status, path and rule message
kubectl -n edge logs deploy/waf --since=1h | grep '"transaction"' \
  | grep -oE '"uri":"[^"]*"|"http_code":[0-9]+|"ruleId":"[0-9]+"|"message":"[^"]*"'
```

How to read the results and tune the rules is covered in [guide 9](09-waf-firewall.md).

## 2.5 Kubernetes events and health

Kubernetes records an **event** whenever something happens to a pod: scheduled, image pulled,
started, probe failed, killed for using too much memory (*OOMKilled*), restarted.

```bash
kubectl get events -A --sort-by=.lastTimestamp | tail -30     # latest events, all namespaces
kubectl -n ticketing get events --sort-by=.lastTimestamp       # one namespace
kubectl -n ticketing describe pod -l app=ticket-service        # one pod: its state, last crash reason and its events
```

Events are kept for about **one hour**. An empty answer (`No resources found`) simply means nothing has
happened recently, which is good.

**Health checklist:**

```bash
kubectl get pods -A                          # all Running, READY 1/1
kubectl get pods -A | awk '$5 > 0'           # pods that restarted (column RESTARTS); a few restarts right after start-up are normal
kubectl get certificate -A                   # all READY True
bash scripts/e2e.sh                          # full functional test; must end with "0 failed"
```

**Why did a pod restart?** `kubectl -n <ns> describe pod <pod-name>` → look at *Last State*: `Reason`
(`Error`, `OOMKilled`) and `Exit Code`; then read `kubectl -n <ns> logs <pod-name> --previous`.

**Certificate events:**

```bash
kubectl describe certificate -A | grep -E '^Name:|Not After|Renewal Time|Ready|Issuing'
kubectl -n ticketing describe certificate ticket-service    # full detail and events
```

## 2.6 Business and login events

**Ticket history.** Every ticket keeps its own audit trail (created, claimed, unlocked, approved,
rejected, more details requested, responded), with who did it, when and the comment. It is shown under
each ticket in the UI, returned by the API in the `events` field, and stored in MongoDB (see
[guide 3](03-database-investigation.md#33-mongodb-ticket-details-and-history)).

**Login events (Keycloak)** are switched off by default. To record sign-ins and failed passwords:

1. Admin console → realm **ticketing** → **Realm settings** → **Events** tab.
2. **User events settings** → switch **Save events** on, choose an expiration (e.g. 30 days) → **Save**.
3. Optionally **Admin events settings** → **Save events** on (records changes made by administrators).
4. To view them: left menu → **Events** → *User events* (filter by user, type `LOGIN_ERROR`, date).

Keycloak already protects accounts from password guessing (*brute force detection* is on): after
repeated failures an account is temporarily locked. Locked users are visible under the user →
**Brute force** status / the *Users* list.

## 2.7 Metrics and traces

**Current CPU and memory** (the small *metrics-server* in k3s provides these):

```bash
kubectl top nodes
kubectl top pods -A
kubectl top pods -A --sort-by=memory
```

Compare memory with each pod's limit (`kubectl -n ticketing describe pod -l app=ticket-service | grep -A2 Limits`);
a pod that reaches its limit is killed and restarted (`OOMKilled`).

**Rate-limit usage of a user:** every `/api` response carries `X-RateLimit-Limit-Minute` and
`X-RateLimit-Remaining-Minute` (the limit is 300 requests per minute per user).

**What to add for history, dashboards, alerts and traces** (not installed today):

| Need | Recommended addition |
|---|---|
| Metrics history + dashboards | Prometheus + Grafana (e.g. the `kube-prometheus-stack` Helm chart) |
| Gateway metrics (requests, latency, status codes per route) | Kong `prometheus` plugin (free in Kong OSS) |
| Java service metrics (JVM, HTTP, DB pool) | `spring-boot-starter-actuator` + `micrometer-registry-prometheus` |
| WAF metrics | nginx `stub_status` endpoint or an nginx exporter |
| Distributed traces | OpenTelemetry: Kong `opentelemetry` plugin + `micrometer-tracing-bridge-otel` in the service, sending to Jaeger or Grafana Tempo |
| Central log search | Grafana Loki or OpenSearch with a log shipper (Fluent Bit) |
| Alerts | Prometheus Alertmanager: pod restarts, certificate expiry < 14 days, 5xx rate, WAF block spikes |

When adding these, remember the Zero Trust rules (guide 8): every new component needs its own
NetworkPolicy allowing exactly the connections it makes. Also note that ticket-service accepts only
mTLS connections from Kong, so a metrics scraper would need a separate management port or its own
allowed certificate.
