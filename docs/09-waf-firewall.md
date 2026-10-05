# 9. Web Application Firewall (WAF)

## 9.1 What it is and why it is there

A **Web Application Firewall** reads every web request *before* it reaches the application and blocks
the ones that look like attacks: SQL injection, cross-site scripting, path traversal, attack tools,
protocol abuse, oversized or malformed requests. It is the first line of defence; the gateway and the
application still do their own checks behind it (guide 8).

This installation uses free, open-source, industry-standard components, running inside the cluster:

| Part | Version | Role |
|---|---|---|
| **nginx** | 1.30.5 | web server/reverse proxy: TLS, limits, forwarding to Kong |
| **ModSecurity** | v3.0.17 | the WAF engine that evaluates rules on each request |
| **OWASP Core Rule Set (CRS)** | 4.30.0 | the community-maintained set of attack-detection rules |
| image | `owasp/modsecurity-crs:nginx-alpine@sha256:f12da37e…` | official image, pinned by digest |

Everything is defined in **`k8s/80-waf.yaml`** (namespace `edge`, Deployment `waf`).

> **Why not Cloudflare (or another cloud WAF)?** A cloud WAF protects a public domain that you own.
> This installation runs on a laptop with the name `ticketing.localtest.me`, so the WAF runs locally.
> When the application gets a real domain, a cloud WAF (Cloudflare, or AWS WAF as in guide 7) can be
> placed *in front of* this one; keeping both gives two independent layers.

## 9.2 Where it sits

```
Browser ──HTTPS:8443──► k3d load balancer :443 ──► WAF pod :8443 ──HTTPS (verified)──► Kong ──► …
                                                    namespace edge          namespace gateway
```

* The WAF is the **only** component with a public port. Kong is internal only (`ClusterIP`), and a
  NetworkPolicy lets **only the WAF** connect to Kong. Nothing can reach the application without
  passing the firewall.
* The WAF ends the browser's TLS connection (it must decrypt to inspect), then opens a **new** TLS
  connection to Kong and **verifies Kong's certificate** against the cluster CA. A request is therefore
  encrypted on every hop.
* It gives each request an ID and forwards it as `X-Correlation-ID` (guide 2).

## 9.3 How a request is checked (in order)

1. **TLS**: only TLS 1.2/1.3 with modern ciphers.
2. **Host check**: the request must be for `ticketing.localtest.me` (or `localhost`); anything else gets
   **421** (stops scanners that hit IP addresses or forged Host headers).
3. **Size and time limits** (nginx): body ≤ 1 MB (else **413**); headers ≤ 16 KB; slow clients are cut
   off after 10 s (protects against "slowloris" attacks).
4. **Custom rules** (our application's "allow list", 9.4): unknown path → **404**, wrong method →
   **403**, non-JSON body to the API → **415**.
5. **OWASP CRS anomaly scoring**: each matching rule adds points according to its severity (critical
   5, error 4, warning 3, notice 2). If a request's total reaches **5** (the *inbound anomaly
   threshold*), it is blocked with **403**. One clear attack (critical) is enough; a single weak
   signal (warning) is not, which keeps false alarms low.
6. Responses are also scored (data-leak rules, threshold 4).
7. The clean request is forwarded to Kong.

## 9.4 Our custom rules

In `k8s/80-waf.yaml` → ConfigMap `waf-config` → `ticketing-before.conf` (loaded before the CRS rules):

| ID | Rule | Effect |
|---|---|---|
| 1000100 | HTTP methods: `GET HEAD POST OPTIONS` everywhere; additionally `PUT PATCH DELETE` under `/auth/admin/` (Keycloak admin console) | other methods → 403 (by CRS rule 911100) |
| 1000110 | **Path allow list**: `/`, `/index.html`, `/app.js`, `/styles.css`, `/favicon.ico`, `/api/…`, `/auth…` | anything else → 404 before it reaches Kong |
| 1000120 | API bodies must be `application/json` (requests without a body are fine) | → 415 |
| 1000200 | **False-positive exclusion FP-1**: rule 920180 ("POST without Content-Length") is switched off for `/api/approvals/tickets/<id>/claim` and `/unlock` only | lets body-less approver actions through cleanly |

> **When the UI gets a new file** (e.g. `logo.svg`), add it to rule 1000110, or the WAF answers 404
> for it. This is the price of a positive security model, and it is worth it: scanners probing for
> `/.git`, `/wp-admin`, `/actuator` never reach the application.

## 9.5 Settings you can change

Environment variables of the `waf` container in `k8s/80-waf.yaml`:

| Variable | Current | Meaning |
|---|---|---|
| `MODSEC_RULE_ENGINE` | `On` | `On` = block; `DetectionOnly` = only log (emergency/tuning mode); `Off` = no WAF |
| `BLOCKING_PARANOIA` / `DETECTION_PARANOIA` | `1` | CRS strictness 1–4. Higher catches more but produces more false positives. Level 1 is the recommended start. |
| `ANOMALY_INBOUND` | `5` | score at which a request is blocked (lower = stricter) |
| `ANOMALY_OUTBOUND` | `4` | same for responses |
| `MODSEC_REQ_BODY_LIMIT` | `1048576` | maximum request body (bytes) ModSecurity accepts |
| `MODSEC_AUDIT_LOG_PARTS` | `AHZ` | what the audit log records: **no headers or bodies** (they contain passwords and tokens). Do not add `B`, `C` or `I` in production. |

### How to apply a change

```bash
# 1. edit k8s/80-waf.yaml (rules or settings)
# 2. if you changed the ConfigMap (rules/nginx config): increase the number in
#      ticketing/config-version: "2"   ->  "3"
#    (rule files are mounted in a way that does not update a running pod; the new number forces a restart)
# 3. apply and wait
kubectl apply -k k8s
kubectl -n edge rollout status deploy/waf
# 4. prove nothing broke and attacks are still blocked
bash scripts/e2e.sh
```

If the new configuration is invalid, the new WAF pod does not become *Ready* and the **old pod keeps
serving** (rolling update). Check `kubectl -n edge logs deploy/waf` for the error, fix, apply again.

## 9.6 Testing the WAF

These requests must be blocked; `scripts/e2e.sh` runs most of them automatically (section
"Web Application Firewall"):

```bash
B=https://ticketing.localtest.me:8443
c() { printf '%-28s %s\n' "$1" "$(curl -sk -o /dev/null -w '%{http_code}' "${@:2}")"; }

c "normal page (200)"          $B/
c "SQL injection (403)"        "$B/api/tickets?status=1%27%20OR%20%271%27=%271"
c "XSS in JSON (403)"          -X POST -H 'Content-Type: application/json' -d '{"title":"<script>alert(1)</script>"}' $B/api/tickets
c "attack tool (403)"          -A "sqlmap/1.7" $B/
c "unknown path (404)"         $B/.git/config
c "path traversal (400)"       --path-as-is "$B/auth/../../etc/passwd"
c "TRACE method (405)"         -X TRACE $B/
c "PUT on the API (403)"       -X PUT $B/api/tickets
c "text body to API (415)"     -X POST -H 'Content-Type: text/plain' -d x $B/api/tickets
c "unknown host (421)"         -H 'Host: evil.example' $B/
```

Every one of these was verified on this installation. A normal request (`200`, or `401` for the API
without a token) must still pass.

## 9.7 Reading the WAF logs

```bash
kubectl -n edge logs deploy/waf --since=1h | grep -v '"transaction"'    # access lines (every request)
kubectl -n edge logs deploy/waf --since=1h | grep '"transaction"'       # security records (blocked/suspicious)
```

**Who blocked it?** In an access line, `status` is what the user received and `upstream` what Kong
returned. `status=403 upstream=-` → the **WAF** blocked it; `status=403 upstream=403` → the
**application** refused it (e.g. wrong role).

**Which rule?** List the important fields of recent security records:

```bash
kubectl -n edge logs deploy/waf --since=1h | grep '"transaction"' \
  | grep -oE '"unique_id":"[^"]*"|"uri":"[^"]*"|"http_code":[0-9]+|"ruleId":"[0-9]+"|"message":"[^"]*"'
```

Example:

```
"unique_id":"179118018796.865719"
"uri":"/"
"http_code":403
"message":"Found User-Agent associated with security scanner"
"ruleId":"913100"
```

Rule ID ranges tell you the category: 911 methods, 913 scanners, 920 protocol, 921 request smuggling,
930 path traversal/file access, 931 remote file inclusion, 932 command injection, 933 PHP, 941 XSS,
942 SQL injection, 943 session fixation, 944 Java attacks, 949 the final "score too high" decision,
95x response (data leakage). Our own rules are 1000xxx.

Only requests with a 4xx (except 404) or 5xx status, or with matched rules, are recorded. To keep
personal data out of the logs the records contain the request line but no headers or bodies.

## 9.8 Handling a false positive (a legitimate request is blocked)

1. **Get the facts.** Ask for the time and, if possible, the `X-Correlation-ID`. Find the audit record
   (9.7) and note the **rule ID**, the **path**, and the matched **variable** (inside `"match"`, e.g.
   `ARGS:comment` or `REQUEST_HEADERS:User-Agent`).
2. **Confirm it is legitimate.** Reproduce it; make sure it is not an actual attack.
3. **Add the narrowest exclusion** to `ticketing-before.conf`, with the next free ID (1000210, 1000220…)
   and a comment explaining why:

   * Turn one rule off for one path (this is how FP-1 is written):

     ```
     SecRule REQUEST_URI "@rx ^/api/approvals/tickets/[0-9a-fA-F-]{36}/(?:claim|unlock)$" \
         "id:1000200,phase:1,pass,nolog,t:none,ctl:ruleRemoveById=920180"
     ```

   * Better still, stop one rule inspecting **one field** on one path, keeping it active for
     everything else (example: free text in `comment` triggering an SQL rule):

     ```
     SecRule REQUEST_URI "@rx ^/api/approvals/tickets/[^/]+/decision$" \
         "id:1000210,phase:1,pass,nolog,t:none,ctl:ruleRemoveTargetById=942100;ARGS:json.comment"
     ```

   * Never switch a rule off everywhere, never raise the paranoia threshold for one complaint, and
     never set the whole engine to `DetectionOnly` as a permanent fix.
4. Bump `ticketing/config-version`, apply, and run `bash scripts/e2e.sh` (9.5).
5. Re-test the original request; then check the audit log shows no other rule firing for it.

**Real example (FP-1):** after the WAF went live, the audit log showed rule **920180** ("POST without
Content-Length and Transfer-Encoding headers", 3 points) on successful `claim`/`unlock` calls. They are
POSTs without a body; some clients then omit `Content-Length`. 3 points alone do not block, but one
more weak signal would have blocked an approver, so the rule was excluded for exactly those two
endpoints.

## 9.9 Emergency: switch to detection-only

If the WAF wrongly blocks a business-critical function and there is no time for a precise exclusion:

```bash
kubectl -n edge set env deploy/waf MODSEC_RULE_ENGINE=DetectionOnly
kubectl -n edge rollout status deploy/waf
```

The WAF now **logs** everything it would have blocked but blocks nothing (the custom path/method rules
are part of ModSecurity too, so they also only log). The nginx limits (host check, sizes, timeouts)
still apply. Because there is a single WAF copy, requests can fail for a few seconds while the new pod
takes over; wait until `kubectl -n edge get pods` shows exactly one `waf` pod. Fix the false positive
(9.8), then restore:

```bash
kubectl -n edge set env deploy/waf MODSEC_RULE_ENGINE=On
```

and make sure `k8s/80-waf.yaml` still says `On` (the next `kubectl apply -k k8s` would otherwise
restore whatever the file says).

## 9.10 Keeping the WAF up to date

New CRS releases add detections. Every few months:

1. Pull the newest image and note its digest:
   `docker pull owasp/modsecurity-crs:nginx-alpine && docker image inspect owasp/modsecurity-crs:nginx-alpine --format '{{index .RepoDigests 0}}'`
2. Put the new digest into `k8s/80-waf.yaml`, set `MODSEC_RULE_ENGINE` to `DetectionOnly`, apply.
3. Run `bash scripts/e2e.sh` and normal user tests; watch the audit log for a few days.
4. Fix false positives (9.8), set `MODSEC_RULE_ENGINE` back to `On`, apply, run `e2e.sh` again.

## 9.11 What this WAF does not do

* **Volumetric DDoS protection**: a laptop or single cluster cannot absorb large floods; that needs a
  cloud service (Cloudflare, AWS Shield/WAF; guide 7).
* **Per-IP rate limiting** on the laptop: the local load balancer hides the real client address. Per
  *user* rate limiting is done by Kong (300 requests/minute).
* **Bot management / CAPTCHA**: cloud WAF feature.
* It does not replace secure code: the application validates and authorises everything itself.

## 9.12 Troubleshooting

| Symptom | Cause | Action |
|---|---|---|
| 403 on a legitimate action, `upstream=-` in the WAF log | WAF rule match (false positive) | 9.8 |
| 404 for a new file or path | not in the allow list (rule 1000110) | add it to rule 1000110 |
| 415 from the API | client sent a body that is not JSON | client fix (or rule 1000120) |
| 413 | body larger than 1 MB | intended limit; raise `client_max_body_size` **and** `MODSEC_REQ_BODY_LIMIT` **and** Kong's limit together if really needed |
| 421 | wrong host name in the URL | use `https://ticketing.localtest.me:8443` |
| 502 on every page | WAF cannot reach or verify Kong | `kubectl -n gateway get pods`; `kubectl -n edge logs deploy/waf` (look for `upstream SSL certificate verify error` → `bash scripts/reload-certs.sh`) |
| Site unreachable | WAF pod not running/ready | `kubectl -n edge get pods`; `kubectl -n edge describe pod -l app=waf`; `kubectl -n edge logs deploy/waf` |
