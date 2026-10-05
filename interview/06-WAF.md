# WAF: ModSecurity + OWASP Core Rule Set

## Concepts you must own

* **What a WAF is / is not:** a layer-7 filter that inspects HTTP requests (and optionally responses)
  against rules. It reduces exploitability of *known attack classes*; it does not fix vulnerable code,
  does not do authentication, and does not stop volumetric DDoS.
* **Negative vs positive security model:** negative = block known-bad patterns (CRS); positive = allow
  only known-good (paths, methods, content types, schemas). Best results combine both.
* **ModSecurity v3 (libmodsecurity) + connector:** engine embedded in nginx; rules in SecLang; five
  processing **phases** (1 request headers, 2 request body, 3 response headers, 4 response body,
  5 logging). Actions: `deny`, `pass`, `block`, `ctl`, `setvar`, `chain`.
* **OWASP CRS anomaly scoring:** rules add points by severity (critical 5, error 4, warning 3,
  notice 2); rule 949110 blocks when the inbound score ≥ threshold. **Paranoia levels** 1–4 trade
  detection for false positives.
* **Rule exclusions:** runtime (`ctl:ruleRemoveById`, `ctl:ruleRemoveTargetById`, placed *before* CRS)
  vs configure-time (`SecRuleRemoveById`, `SecRuleUpdateTargetById`, placed *after* CRS).
* **TLS termination:** the WAF must decrypt to inspect; re-encrypt to the backend for end-to-end
  encryption.
* **Audit logging and privacy:** what you log (headers/bodies) can itself become a data leak.

## How this application applies them

| Concept | Implementation | Where |
|---|---|---|
| Single entry, terminate + re-encrypt | nginx :8443 TLS 1.2/1.3 → `proxy_pass https://kong` with `proxy_ssl_verify on`, CA, SNI name | `k8s/80-waf.yaml` (default.conf.template) |
| Positive model | rule 1000110 path allow-list (404), 1000100 methods, 1000120 JSON-only API (415), host check (421) | `ticketing-before.conf` |
| Negative model | CRS 4.30 at PL1, inbound threshold 5, outbound 4 | env vars in `80-waf.yaml` |
| Tuned exclusion | FP-1: `ctl:ruleRemoveById=920180` only for claim/unlock | rule 1000200 |
| Resource limits | 1 MB body, 10 s header/body timeouts, 16 KB headers | nginx directives |
| Privacy | `MODSEC_AUDIT_LOG_PARTS=AHZ` (no headers/bodies) | env |
| Origin lockdown | Kong `ClusterIP` + NetworkPolicy `kong-ingress-from-waf` | `60-kong.yaml`, `70-network-policies.yaml` |
| Supply chain | image pinned by digest | `80-waf.yaml` |

## Local (narrowed) → Production (unwrapped)

> Local design unchanged; production items are **proposals requiring approval**.

| Q | Aspect | Local (narrowed) | Production (unwrapped) | Validate in production |
|---|---|---|---|---|
| Q1 | Anomaly scoring | PL1, threshold 5 | same start; PL2 detection-only on sensitive routes | block-rate and FP-rate dashboards |
| Q2 | Positive model | hand-written allow-list | generated from OpenAPI in CI, versioned with the app | e2e per route in the pipeline |
| Q3 | Exclusions | one exclusion (FP-1) | exclusion register with owner and review date | quarterly exclusion review |
| Q4 | TLS termination | private CA cert, laptop | ALB/ACM public cert terminates first; WAF re-terminates with internal cert; or AWS WAF only | `openssl` chain checks on each hop |
| Q5 | Audit privacy | parts AHZ, stdout | same, shipped to SIEM with retention | sample audit for absence of credentials |
| Q6 | Emergency mode | `kubectl set env` | change-managed toggle with auto-expiry alert | alert when engine ≠ On |
| Q7 | Coverage | WAF + app controls | + AWS WAF managed rules, bot control, Shield | mapping of OWASP Top 10 to layers |
| Q8 | Per-IP limits | impossible (masqueraded IPs) | real client IP from ALB `X-Forwarded-For` + per-IP limits on login/token | IP shown correctly in WAF logs |
| Q9 | CRS upgrades | manual digest bump | Renovate PR → detection-only canary deployment → promote | canary FP comparison |
| Q10 | Local vs cloud WAF | local only (no public domain) | cloud WAF at perimeter + in-cluster positive model | both layers' logs correlated by request ID |
| Q11 | Writable root FS | accepted for the image | init-container rendering or baked image, RO root FS | policy engine enforces RO root FS |

## Prove it

```bash
B=https://ticketing.localtest.me:8443
curl -sk -o /dev/null -w '%{http_code}\n' "$B/api/tickets?status=1%27%20OR%20%271%27=%271"   # 403 (CRS 942xxx)
curl -sk -o /dev/null -w '%{http_code}\n' -A "sqlmap/1.7" $B/                                  # 403 (913100)
curl -sk -o /dev/null -w '%{http_code}\n' $B/.git/config                                       # 404 (1000110)
curl -sk -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: text/plain' -d x $B/api/tickets   # 415 (1000120)
curl -sk -o /dev/null -w '%{http_code}\n' -H 'Host: evil.example' $B/                          # 421
kubectl -n edge logs deploy/waf --since=10m | grep '"transaction"' | grep -oE '"ruleId":"[0-9]+"' | sort | uniq -c
kubectl -n gateway get netpol kong-ingress-from-waf -o yaml | grep -A6 'from:'
```

---

## Questions

### Q1. Explain anomaly scoring. Why is it better than "block on first match"? ★★★
**30-second headline:** Rules add points by severity and the request is blocked when the total reaches the threshold (5): one clear attack blocks, a single weak signal does not, which keeps false positives low.
**Weak answer (what fails):** "It blocks anything that matches a rule."
**Strong answer:** Each rule contributes points by severity; a request is blocked only when the sum
crosses the threshold (here 5). One critical signature (SQLi/XSS) blocks immediately; a single weak
heuristic (e.g. 920180, warning = 3) does not. This reduces false positives, lets you tune by
threshold, and gives a richer log (all matched rules, not just the first). Blocking happens in rule
949110 at the end of phase 2.
**Prove it:** the FP-1 story: 920180 matched on legitimate claim calls (3 points) without blocking.
**Follow-ups / traps:** "So a determined attacker splits payloads to stay under 5?" → each critical rule
alone is 5; splitting a SQLi across parameters still triggers per-parameter rules; raise paranoia for
sensitive endpoints.

### Q2. Positive security model: what did we allow-list, what does it cost, and how do you keep it from rotting? ★★★★
**30-second headline:** Allowed: our paths, methods and JSON bodies only; cost is coupling every new route or asset to a WAF change, so generate the allow-list from the API contract and test every route in CI.
**Weak answer (what fails):** Ignoring the maintenance cost of a positive model.
**Strong answer:** Paths (`/`, five static files, `/api/`, `/auth`), methods (GET/HEAD/POST/OPTIONS,
+PUT/PATCH/DELETE under `/auth/admin/`), JSON-only API bodies, host names. Cost: every new UI asset or
endpoint needs a WAF change: coupling between app releases and WAF config. Keep it alive by
generating the allow-list from the API contract (OpenAPI) in CI, or by moving fine-grained positive
validation to the gateway (request validator) where it is versioned with the API. e2e tests catch
breakage (a new path returns 404).
**Follow-ups / traps:** "Why 404 not 403 for unknown paths?" (indistinguishable from not-found; does not
advertise a WAF rule.)

### Q3. Runtime vs configure-time exclusions: where must each go, and why does order matter? ★★★★★
**30-second headline:** ctl: exclusions change the current transaction, so they must run before the target rule; SecRuleRemoveById/UpdateTarget change loaded rules, so they come after it. Wrong placement silently does nothing.
**Weak answer (what fails):** "Just add SecRuleRemoveById anywhere."
**Strong answer:** `ctl:` actions alter the *current transaction*, so the rule carrying them must
execute **before** the CRS rule it disables: same phase or earlier, loaded earlier (CRS "before"
plugin files). `SecRuleRemoveById` / `SecRuleUpdateTargetById` modify rules *at load time*, so they
must appear **after** the target rule is defined. Putting a `ctl:ruleRemoveById` after CRS silently
does nothing for that request, a classic tuning mistake.
**Applied here:** rule 1000200 in `ticketing-before.conf` (phase 1) removes 920180 (phase 1/2).
**Follow-ups / traps:** "Prefer `ruleRemoveTargetById` over `ruleRemoveById`: why?" (keeps the rule
active for all other fields).

### Q4. The WAF terminates TLS. Isn't that a man-in-the-middle by design? How is Zero Trust preserved? ★★★★
**30-second headline:** It is an authorised TLS termination point: hardened, minimal and re-encrypting with verification to Kong; behind it every layer still authenticates, so a compromised WAF cannot bypass authorisation.
**Weak answer (what fails):** "TLS passthrough is more secure" (then the WAF can't inspect).
**Strong answer:** Yes, it is an intentional, authorised TLS termination point: it holds the edge
private key, so it is a high-value asset (non-root, minimal capabilities, no SA token, pinned image).
Zero Trust continues behind it: the WAF opens a *new* TLS session and **verifies Kong's certificate**
against the private CA with the expected name (`proxy_ssl_verify on`, `proxy_ssl_name`); Kong accepts
connections only from the WAF pod; Kong and the service still authenticate the user themselves. A
compromised WAF can read traffic (tokens) but cannot bypass service-side authorisation.
**Follow-ups / traps:** "Why not TLS passthrough?" (cannot inspect; then the WAF is useless at L7.)

### Q5. Why did we strip headers and bodies from the audit log? What do you lose? ★★★★
**30-second headline:** Headers and bodies contain passwords, tokens and PII; logging them turns the log store into a credential store. Keep matched rules and request line; reproduce false positives elsewhere.
**Weak answer (what fails):** "Log everything for forensics."
**Strong answer:** Bodies contain passwords (Keycloak login form) and personal data; headers contain
bearer tokens and session cookies. Logging them makes the log store a credential and PII store with
different access controls and retention, a GDPR and security liability. We keep part A (client, URI,
IDs) and H (matched rules/messages). Loss: harder false-positive analysis; mitigate by reproducing in
a lab or temporarily enabling sanitised capture in a non-production environment.
**Applied here:** `MODSEC_AUDIT_LOG_PARTS=AHZ`.

### Q6. A business-critical form is being blocked in production on a Friday evening. Walk me through it. ★★★★
**30-second headline:** Correlation ID → audit record → rule, path, variable → narrowest exclusion → deploy → test; if no time, DetectionOnly with an expiry and an incident record, never a global threshold change.
**Weak answer (what fails):** Turning the WAF off.
**Strong answer:** Get the correlation ID/time → find the audit record → rule ID, path, variable →
confirm legitimacy → add the narrowest exclusion (`ruleRemoveTargetById` for that field on that path)
→ deploy → e2e. If no time: switch to `DetectionOnly` (logs, no blocking) with an incident ticket and
a timed rollback; never lower the paranoia/threshold globally. Communicate the reduced protection.
**Prove it:** `kubectl -n edge set env deploy/waf MODSEC_RULE_ENGINE=DetectionOnly` (and back to `On`);
tested procedure in `docs/09-waf-firewall.md` §9.9.

### Q7. Which attacks does this WAF *not* stop, and where are they handled instead? ★★★★
**30-second headline:** Broken access control, business-logic abuse, credential stuffing at scale, volumetric DDoS and zero-days: handled by service authorisation, domain rules, Keycloak brute-force protection and a cloud edge.
**Weak answer (what fails):** "The WAF covers the OWASP Top 10."
**Strong answer:** Broken access control / IDOR (handled by service ownership checks, 404s, RLS);
business-logic abuse (self-approval, handled by domain rule + DB constraint); credential stuffing at
scale (Keycloak brute-force detection; per-IP limits need real client IPs); volumetric DDoS (needs a
CDN/cloud WAF); attacks inside TLS to other hosts; zero-days without signatures. Mapping OWASP Top 10
to layers is the point of the question.

### Q8. Per-IP rate limiting at the WAF: why didn't we do it? ★★★★
**30-second headline:** On k3d the load balancer masquerades client IPs, so a per-IP limit would throttle everyone together; per-user limits sit in Kong, per-tenant in the service, per-IP belongs at a cloud edge with real client IPs.
**Weak answer (what fails):** "We forgot."
**Strong answer:** On k3d the service load balancer masquerades source IPs, so every client appears as
the same address; a per-IP limit would throttle everyone together. Per-user limiting is done at Kong
on the verified JWT `sub`. In cloud, keep real IPs (ALB `X-Forwarded-For` + `set_real_ip_from`, or
`externalTrafficPolicy: Local`) and add per-IP limits at the edge for unauthenticated endpoints
(login, token).
**Prove it:** WAF log `client=10.42.0.1` for every request.

### Q9. How would you roll out a CRS upgrade or a paranoia-level increase safely? ★★★★
**30-second headline:** New digest in detection-only (or detection paranoia above blocking), replay e2e and real traffic, add narrow exclusions, then enable blocking; canary if possible.
**Weak answer (what fails):** Upgrading the rule set in place on a Friday.
**Strong answer:** New digest → `DetectionOnly` (or run PL2 as *detection paranoia* while blocking at
PL1: CRS supports `DETECTION_PARANOIA` > `BLOCKING_PARANOIA`) → replay e2e and real traffic → analyse
audit log for new matches → add exclusions → switch on → monitor block rate. Canary: run two WAF
deployments and split traffic.
**Applied here:** env `BLOCKING_PARANOIA` / `DETECTION_PARANOIA` both 1.

### Q10. Defend running ModSecurity in-cluster versus AWS WAF or Cloudflare. ★★★★
**30-second headline:** In-cluster: offline-capable, versioned with the app, custom positive model, no per-request cost; cloud: DDoS, bot management, reputation. Production wants both.
**Weak answer (what fails):** Picking one and dismissing the other.
**Would I do it again?** Yes, given no public domain; in production I would put a cloud WAF in front, not replace this.
**Strong answer:** In-cluster: works offline/on-prem, version-controlled with the app, custom positive
rules close to the code, no per-request cost; but you operate it, it scales with your pods, it cannot
absorb DDoS. Cloud WAF: managed rules, bot management, global edge, DDoS absorption, IP reputation;
but vendor lock-in, less transparency, needs a public domain. Best for production: cloud WAF at the
perimeter + in-cluster positive model (defence in depth). This repo chose local because there is no
public domain (documented decision).

### Q11. The WAF image runs with a writable root filesystem. Risk and remediation? ★★★
**30-second headline:** The image renders config into its root filesystem at start; fix with an init container writing to emptyDir or a derived image with baked config, then make the root read-only.
**Weak answer (what fails):** "It's a vendor image, nothing to do."
**Strong answer:** The entrypoint renders templates into `/etc/nginx` and copies rules into
`/etc/modsecurity.d`; a read-only root FS breaks it. Risk: an attacker with code execution could alter
config in that container instance (not persistent across restarts). Remediation: render config in an
init container into an `emptyDir` and mount the rest read-only, or build a custom image with the final
config baked in.

### Q12. Rapid fire
* Phase in which `REQUEST_FILENAME` path rules run here? → phase 1.
* Rule range for SQLi? → 942xxx; XSS 941xxx; scanners 913xxx; protocol 920xxx; final decision 949110.
* What does 421 mean? → Misdirected Request: wrong host for this server.
* Body > 1 MB? → 413 from nginx before ModSecurity.
* Where is Kong reachable from? → only the WAF pod (NetworkPolicy), ClusterIP service.
