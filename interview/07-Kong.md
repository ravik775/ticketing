# Kong API Gateway (OSS 3.9, DB-less)

## Concepts you must own

* **Gateway responsibilities:** routing, authentication offload, quotas/rate limiting, request
  shaping, observability hooks, TLS to upstreams. **Not** business authorisation.
* **Entities:** Service (upstream), Route (matching rules → service), Plugin (scoped globally, per
  service, per route, per consumer), Consumer (an identity the gateway knows), Certificate /
  CA certificate.
* **Deployment modes:** traditional (Postgres-backed), **DB-less** (declarative YAML loaded at start),
  hybrid (control plane + data planes). DB-less = immutable config, no DB to operate, config change =
  reload/restart.
* **Plugin execution order** is by **priority** (higher runs first) within a phase: e.g. `pre-function`
  1 000 000, `correlation-id` 100 001, `jwt` 1450, `request-size-limiting` 951, `rate-limiting` 910,
  `response-transformer` 800.
* **OSS `jwt` plugin:** verifies signature + selected registered claims using a secret/public key
  looked up by a claim (`key_claim_name`, default `iss`). No JWKS discovery, no `kid` selection, no
  audience check (OIDC plugin is Enterprise).
* **Rate-limiting:** fixed windows; `limit_by` consumer / credential / ip / header / path / service;
  policies `local` (per node, fast, inaccurate across replicas), `cluster` (DB, not in DB-less),
  `redis` (shared, accurate).

## How this application applies them

| Concept | Implementation | Where |
|---|---|---|
| DB-less, config as code | `kong.yml` rendered by script into a Secret, mounted read-only; admin API off | `scripts/render-kong.sh`, `k8s/60-kong.yaml` |
| Routes | `/api` → ticket-service (mTLS), `/auth` → Keycloak (TLS verified), `/` → UI | `render-kong.sh` |
| Authentication | `jwt` plugin, `key_claim_name: iss`, `claims_to_verify: [exp]`, RS256 public key of the realm on one consumer | `render-kong.sh` |
| Per-user quota | `pre-function` copies verified-later `sub` into `X-Rate-Limit-Subject`; `rate-limiting` 300/min `limit_by: header` | `render-kong.sh` |
| Upstream mTLS | `client_certificate` (CN=kong-gateway) + `ca_certificates` + `tls_verify: true` | `render-kong.sh` |
| Hygiene | 1 MB body limit, correlation ID echoed downstream, HSTS + nosniff | plugins |
| Exposure | Service `ClusterIP`; only WAF may connect | `60-kong.yaml`, NetworkPolicy |

## Local (narrowed) → Production (unwrapped)

> Local design unchanged; production items are **proposals requiring approval**.

| Q | Aspect | Local (narrowed) | Production (unwrapped) | Validate in production |
|---|---|---|---|---|
| Q1 | Mode | DB-less, config rendered by script | DB-less or hybrid with decK from Git; config validated in CI (`kong config parse`) | config drift check |
| Q2 | Per-user limit | pre-function trick, `local` policy | same trick + `redis` policy (ElastiCache) | spoofed-header canary; quota consistent across replicas |
| Q3 | Key rotation | static key; re-render manually | coordinated rotation runbook or JWKS-aware validation (Enterprise OIDC/custom plugin) | rotation rehearsal in staging |
| Q4 | Claims checked | `exp` only (service checks the rest) | same contract, documented; add audience in IdP | ID-token canary |
| Q5 | Accuracy | 1 replica: exact | Redis shared counters; decide fail-open/closed | load test at limit across replicas |
| Q6 | Upstream mTLS | cert-manager certs embedded | same, or mesh-issued identities; automatic reload | upstream cert expiry metric |
| Q7 | Config rollout | restart, 1 replica (brief gap) | ≥ 2 replicas, PDB, status-endpoint readiness, `preStop` | zero-error rollout test |
| Q8 | Route governance | three routes | route ownership, review gates, global security plugins | route inventory diff per release |
| Q9 | Resilience | defaults (60 s, retries 5) | explicit timeouts, retries 1, upstream health checks | fault-injection tests |
| Q10 | Admin API | off | off; status listener for metrics only | port scan shows no admin port |
| Q11 | Scaling | 1 worker process | workers = vCPUs, HPA, sized by load test | RPS/vCPU benchmark |

## Prove it

```bash
kubectl -n gateway get svc kong                                         # TYPE ClusterIP
kubectl -n gateway get secret kong-declarative-config -o 'jsonpath={.data.kong\.yml}' | base64 -d | grep -E '^\s+- name: ' | sort | uniq -c
T=$(curl -sk https://ticketing.localtest.me:8443/auth/realms/ticketing/protocol/openid-connect/token -d grant_type=password -d client_id=ticketing-ui -d username=bob -d 'password=Passw0rd!' -d scope=openid | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)
curl -sk -D - -o /dev/null -H "Authorization: Bearer $T" https://ticketing.localtest.me:8443/api/me | grep -iE 'ratelimit|correlation'
curl -sk -o /dev/null -w '%{http_code}\n' https://ticketing.localtest.me:8443/api/me     # 401 from the jwt plugin
kubectl -n gateway logs deploy/kong --tail=5
```

---

## Questions

### Q1. DB-less vs traditional vs hybrid Kong: why DB-less here, and when does it stop being the right choice? ★★★
**30-second headline:** DB-less gives immutable, Git-reviewed config with no database to run; it stops fitting with many teams, frequent changes or Enterprise features, where hybrid mode (still driven from Git) wins.
**Weak answer (what fails):** "DB-less is just for demos."
**Would I do it again?** Yes; config-as-code was worth the restart-to-change cost.
**Strong answer:** DB-less: no database to secure/back up, config is code (reviewable, reproducible),
data planes are immutable and horizontally scalable. Costs: no runtime admin changes (here a restart),
no `cluster` rate-limit policy, all config in memory, large configs slow start. Hybrid becomes right
with many gateways/teams, frequent config changes, or Enterprise features; keep config in Git anyway
(decK) so the DB is a cache of Git, not the source of truth.

### Q2. Explain the pre-function + rate-limiting trick. Prove it cannot be abused to exhaust someone else's quota or to bypass yours. ★★★★★
**30-second headline:** pre-function runs first and overwrites the subject header from the token; jwt then rejects forged tokens before rate-limiting counts, so nobody can target or escape a quota.
**Weak answer (what fails):** Rate limiting by the Authorization header or by IP.
**Would I do it again?** Yes; it closed a real fairness hole with three plugin settings, and the e2e canary proves it.
**Strong answer:** OSS cannot key limits on a JWT claim, so `pre-function` (priority 1 000 000, runs
first) decodes the token payload (unverified), and **overwrites or clears** `X-Rate-Limit-Subject`.
Then `jwt` (1450) verifies the signature: forged tokens are rejected **before** `rate-limiting` (910)
counts anything. So: (1) a client-supplied header is always replaced → cannot target a victim's
counter; (2) a forged token with a victim's `sub` never reaches the limiter; (3) a valid token always
yields its true `sub`. Residual risk: none for spoofing; the limit is per Kong pod (`local`).
**Prove it:** e2e "a client-supplied subject header is ignored" and "two tokens of the same user draw
from one quota".
**Follow-ups / traps:** "Why not limit by `Authorization` header?" (every login/refresh creates a new
key, trivially multiplying quota.) "Why not by IP?" (k3d masquerades IPs; NAT'd offices.)

### Q3. Keycloak rotates its signing key. What breaks, for how long, and how would you design around it? ★★★★★
**30-second headline:** The OSS jwt plugin holds one static key per issuer and ignores kid, so rotation breaks tokens signed with the other key until config is re-rendered; plan rotation windows or validate via JWKS at the gateway.
**Weak answer (what fails):** "Kong fetches the JWKS automatically."
**Would I do it again?** I would choose a JWKS-aware gateway check (or accept service-only validation) before production key rotation becomes routine.
**Strong answer:** The OSS `jwt` plugin holds one RSA public key bound to `key=iss`; it does not read
`kid` or JWKS. After rotation, tokens signed with the new key fail at Kong (401) until
`render-kong.sh` re-renders; tokens signed with the old key fail if Kong is updated first. A
`jwt_secrets` key must be unique, so two keys for the same issuer is not possible with this plugin.
Options: (a) planned rotation: add the new key in Keycloak as *passive*, update Kong and activate it
together, accept ≤ 5 min of failures for old tokens (access token lifetime); (b) validate via JWKS at
the gateway (Enterprise OIDC plugin or a custom Lua/Go plugin with `kid` lookup); (c) drop gateway
JWT validation and rely on the service (loses early rejection).
**Follow-ups / traps:** "The service is fine: why?" (Spring's Nimbus decoder fetches JWKS by `kid`.)

### Q4. Why doesn't Kong check `aud`, `typ` or `azp`, and is that a vulnerability? ★★★★
**30-second headline:** The OSS plugin checks the signature and only the claims listed (here exp); aud/typ/azp are enforced by the service, so this is a documented contract, not a hole.
**Weak answer (what fails):** Claiming Kong validates nbf or the audience here.
**Strong answer:** The OSS plugin verifies the signature plus only the registered claims listed in
`claims_to_verify`; this configuration lists **only `exp`** (`nbf` is supported but not enabled here, and
`aud`/`typ`/`azp` are not supported at all). An ID token or a token for another client of the realm passes Kong. It is not a vulnerability **in this design** because the
service enforces `iss`, `typ=Bearer`, `azp=ticketing-ui` (Zero Trust). It *would* be if any upstream
relied on Kong alone; document that the gateway check is "coarse" by contract.
**Prove it:** e2e "an ID token is not accepted": Kong passes it, service returns 401.

### Q5. Rate-limiting accuracy with three Kong replicas and policy `local`: quantify the error and fix it. ★★★★
**30-second headline:** local counters multiply the limit by replica count and fixed windows allow edge bursts; use the redis policy and decide fail-open vs fail-closed.
**Weak answer (what fails):** "Rate limits are exact."
**Strong answer:** Each pod counts separately; with round-robin, a user can make up to 3 × 300 = 900
requests/min (worse with uneven balancing). Fixed windows also allow 2× bursts at window edges.
Fix: `policy: redis` (shared counters, + latency, Redis becomes a dependency: decide fail-open vs
fail-closed via `fault_tolerant`), or divide the limit by replicas (fragile with autoscaling).
Sliding windows need `rate-limiting-advanced` (Enterprise) or a custom solution.

### Q6. How is the upstream mTLS configured in Kong, and what exactly does `tls_verify` check? ★★★★
**30-second headline:** client_certificate presents Kong's identity, ca_certificates sets the trust anchor, tls_verify checks the chain and the upstream hostname in the SAN.
**Weak answer (what fails):** "tls_verify encrypts the connection."
**Strong answer:** Service `client_certificate` → Kong presents CN=kong-gateway; `ca_certificates`
lists the trusted CA; `tls_verify: true` makes Kong validate the upstream chain against that CA and the
**hostname** in the service URL (`ticket-service.ticketing.svc.cluster.local` must be in the cert SAN).
Without `ca_certificates`, Kong would use its system trust store and fail on a private CA.
**Prove it:** `kubectl -n ticketing get secret ticket-service-tls -o jsonpath='{.data.tls\.crt}' | base64 -d | openssl x509 -noout -ext subjectAltName`

### Q7. What happens to in-flight requests when you change Kong config here? Design zero-downtime config rollout. ★★★★
**30-second headline:** A restart-based rollout drains the old pod after the new one is ready; with one replica there is a small gap, so production needs ≥ 2 replicas, PDB, status-based readiness and preStop.
**Weak answer (what fails):** "Config reloads are instant and safe."
**Since implemented:** Kong readiness now uses /status/ready on the status listener.
**Strong answer:** `render-kong.sh` updates the Secret and does `rollout restart`: Kubernetes starts a
new pod, waits for readiness (TCP probe), then terminates the old one; nginx drains on SIGQUIT. With
one replica and a TCP probe that passes before config is fully loaded, there can be a short gap.
Improve: 2+ replicas, PDB, readiness on Kong's status endpoint (`/status` on a dedicated status
listener), `preStop` sleep for endpoint propagation, maxUnavailable 0.

### Q8. Route matching: a new team adds route `/api/admin` to a different service. What could go wrong? ★★★
**30-second headline:** The longer path wins, so a new /api/admin route silently bypasses plugins attached to /api; govern route ownership and attach security plugins at service or global scope.
**Weak answer (what fails):** Assuming plugins inherit from /api.
**Since implemented:** the gateway now uses exactly this mechanism to block /auth/admin and /auth/realms/master.
**Strong answer:** Kong prioritises the longest/most specific path; `/api/admin` would win over `/api`
for those requests, silently diverting traffic and possibly **without the jwt plugin** (plugins are
per route). Also the WAF's allow-list covers `/api/` already, so it would pass. Governance: route
ownership reviews, plugin policies at service level or global, contract tests.

### Q9. Gateway resilience: timeouts, retries, circuit breaking. What is configured and what would you add? ★★★★
**30-second headline:** Defaults (60 s timeouts, 5 retries) are wrong for this API: set explicit timeouts, retries 1 for non-idempotent calls, and upstream health checks for ejection.
**Weak answer (what fails):** "Kong handles resilience."
**Strong answer:** Defaults apply (connect/read/write 60 s, `retries: 5` for idempotent failures on
connection errors). For a single-replica upstream retries add little; risk: retrying non-idempotent
POSTs on certain errors. Add: explicit timeouts per service (e.g. 5 s connect, 15 s read), `retries: 1`,
upstream entity with active/passive health checks to eject bad targets (Kong's circuit breaking), and
idempotency keys for POSTs if retries are needed.

### Q10. Why is the Kong admin API off, and how do you operate without it? ★★★
**30-second headline:** An exposed admin API is a full takeover path; DB-less config from Git removes the need, and metrics come from a separate status listener.
**Weak answer (what fails):** "It's only reachable inside the cluster."
**Strong answer:** The admin API can reconfigure routing and plugins: an unauthenticated admin
listener in OSS is a full compromise path. DB-less config from Git removes the need; observe via logs
and (to add) the Prometheus plugin on a separate status listener.

### Q11. Scaling Kong: what's stateful, what limits throughput, how would you size it? ★★★★
**30-second headline:** Stateless except counters and caches; throughput bound by CPU (TLS, Lua), worker count and upstream latency; size from a load test, workers = vCPUs, HPA on CPU.
**Weak answer (what fails):** Sizing by guess.
**Since implemented:** measured locally (k6, 20 users): Kong peaked at ~0.21 cores at ~37 req/s, p95 109 ms end to end.
**Strong answer:** Stateless except rate-limit counters (local) and caches. Throughput limited by
CPU (TLS, Lua plugins: `pre-function` base64/JSON parsing per request), worker processes
(`KONG_NGINX_WORKER_PROCESSES=1` here), and upstream latency (connections). Size by load test: p95
latency vs RPS per vCPU; HPA on CPU; set worker processes = vCPUs; keep plugin chain lean.

### Q12. Rapid fire
* Which plugin runs first on `/api`? → `pre-function` (then correlation-id, jwt, size limit, rate limit).
* Where does the correlation ID come from? → the WAF (`$request_id`); Kong keeps it and echoes it.
* `/auth` rate limit keyed on? → no consumer there → falls back to IP (600/min).
* Why `strip_path: false`? → upstreams expect the full path (`/api/...`, `/auth/...`).
* What 401 body does Kong return without a token? → `{"message":"Unauthorized"}` (26 bytes in the log).
