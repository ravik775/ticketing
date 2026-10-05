# Security: Zero Trust, mTLS/PKI, threat modelling, cross-cutting controls

## Concepts you must own

* **Zero Trust (NIST SP 800-207):** no implicit trust from network location; every access is
  authenticated, authorised and encrypted per request; least privilege; continuous verification;
  policy enforcement points (PEP) close to resources; assume breach.
* **mTLS & PKI:** handshake (ClientHello → server cert → CertificateRequest → client cert →
  CertificateVerify), chain validation (signature, validity, basic constraints, key usage/EKU, name via
  **SAN**, not CN, for servers), revocation (CRL/OCSP) vs short-lived certificates, key rotation.
* **Threat modelling:** STRIDE (Spoofing, Tampering, Repudiation, Information disclosure, Denial of
  service, Elevation of privilege) per data flow and trust boundary.
* **OWASP API Security Top 10 (2023):** API1 BOLA, API2 broken authentication, API3 property-level
  authorisation, API4 resource consumption, API5 function-level authorisation, API6 business flows,
  API7 SSRF, API8 misconfiguration, API9 inventory, API10 unsafe consumption of APIs.
* **Supply chain:** SBOM, image pinning by digest, signing (Sigstore/cosign), provenance (SLSA),
  dependency scanning.
* **Browser security:** CSP, framing protection, Referrer-Policy, token storage choices.
* **Privacy:** PII classification (email, mobile here), minimisation in logs, retention, erasure.

## How this application applies them

| Control | Implementation | Evidence |
|---|---|---|
| Identity on every hop | WAF→Kong verified TLS; Kong→API mTLS + CN pin; user JWT re-validated by the API | e2e "Zero Trust / network" |
| Least privilege network | default-deny ingress+egress, 19 explicit policies | `kubectl get netpol -A` |
| Least privilege runtime | non-root, no caps, no SA token, seccomp, RO root FS (API) | `docs/08` §8.4 |
| Tenant isolation | token-derived tenant, Hibernate filter, forced RLS, Mongo filter | RLS integration test |
| Function-level authZ | `@PreAuthorize` per tenant role + service re-check + SoD | `ApiSecurityTest` |
| Object-level authZ | ownership → 404, RLS → 404 for other tenants | e2e "another applicant cannot open it" |
| Resource consumption | WAF size/time limits, Kong 1 MB + per-user 300/min, page size ≤ 200 | e2e, `@Max(200)` |
| Edge filtering | ModSecurity + CRS + positive model | e2e WAF section |
| Browser | strict CSP, no framing, no-referrer, tokens in memory, textContent rendering | `ui/default.conf`, `app.js` |
| Privacy | WAF audit log without headers/bodies | `MODSEC_AUDIT_LOG_PARTS=AHZ` |
| Former accepted gaps (closed) | credentials in OpenBao, rotated; admin console internal-only | `docs/08` §8.8, `k8s/15-16`, WAF rule 1000100 |

## Local (narrowed) → Production (unwrapped)

> Local design unchanged; production items are **proposals requiring approval**.

| Q | Aspect | Local (narrowed) | Production (unwrapped) | Validate in production |
|---|---|---|---|---|
| Q1 | Zero Trust PEPs | WAF, Kong, service, RLS | same + AWS WAF/ALB edge, device/context signals if required | NIST 800-207 control mapping in the security architecture doc |
| Q2 | STRIDE | Kong→service hop | full threat model per data flow, reviewed per release | threat model review record |
| Q3 | Workload identity | CN pin `kong-gateway`, private CA in cluster | URI SAN/SPIFFE identities, namespaced issuers or AWS Private CA / mesh | rogue-cert test (issuance denied) |
| Q4 | Revocation | none; 90-day certs | short-lived certs (hours/days) with auto-reload; CA key in HSM/Private CA | cert lifetime dashboard; compromise drill |
| Q5 | OWASP API Top 10 | covered by e2e | + DAST (ZAP) in CI, annual pen-test | scan reports, findings SLA |
| Q6 | XSS impact | CSP strict, tokens in memory | same + CSP reporting endpoint, consider DPoP | CSP violation reports monitored |
| Q7 | Token leakage | proxies terminate TLS, no header logging | same + log pipeline scrubbing, access controls on proxy hosts | log samples audited for tokens (none) |
| Q8 | Supply chain | WAF image pinned only | all images by digest, SBOM, signing + admission verification | admission denies unsigned image |
| Q9 | Accepted gaps | closed locally: OpenBao + External Secrets; admin console blocked at WAF + Kong | AWS Secrets Manager/KMS; internal ALB or VPN for admin | pipeline secret scan; external scan shows no `/auth/admin` |
| Q10 | Security monitoring | logs only | SIEM with detections (see 14-Audit-and-Alerting) | detection tests (purple team) |
| Q11 | PII to approvers | full mobile/email shown | purpose-documented, masked lists, reveal on demand | DPIA, RoPA entry |

## Prove it

```bash
bash scripts/e2e.sh | sed -n '/Zero Trust/,$p'                                  # mTLS + NetworkPolicy proofs
kubectl -n ticketing get secret ticket-service-tls -o jsonpath='{.data.tls\.crt}' | base64 -d | openssl x509 -noout -subject -ext subjectAltName,extendedKeyUsage
kubectl -n gateway  get secret kong-client-tls  -o jsonpath='{.data.tls\.crt}' | base64 -d | openssl x509 -noout -subject -ext extendedKeyUsage   # CN=kong-gateway, clientAuth
curl -sk -D - -o /dev/null https://ticketing.localtest.me:8443/ | grep -iE 'content-security|x-frame|referrer|strict-transport'
kubectl -n ticketing port-forward deploy/ticket-service 18443:8443 & sleep 3; curl -sk https://localhost:18443/api/me; echo "exit=$? (56: no client cert)"; kill %1
```

---

## Questions

### Q1. Map this system to NIST 800-207: where are the PEPs and PDPs, and what is the "trust algorithm"? ★★★★
**30-second headline:** PEPs at WAF, Kong, service and database; identity assertions from Keycloak, workload identity from certificates; missing vs the ideal: device posture and continuous re-evaluation.
**Weak answer (what fails):** Reciting the NIST tenets without mapping them.
**Since implemented:** Keycloak and Kubernetes audit events now feed the SIEM, improving the "monitor" tenet.
**Strong answer:** PEPs: WAF (request hygiene), Kong (authentication + quota), ticket-service (mTLS
caller + JWT + tenant/role/ownership), PostgreSQL RLS (data). PDPs are embedded: Keycloak issues the
identity assertions (groups) consumed by the service's decision logic; there is no external policy
engine. Trust algorithm inputs: user identity and group membership (token), workload identity
(certificate), request attributes (tenant header, path, method). Missing vs the ideal: device posture,
continuous re-evaluation (tokens are valid 5 min after revocation), behavioural signals.

### Q2. STRIDE the Kong → ticket-service hop. ★★★★
**30-second headline:** Spoofing → mTLS + CN pin + NetworkPolicy; tampering → TLS; repudiation → correlation IDs and history; disclosure → TLS and minimal errors; DoS → gateway limits; elevation → roles re-derived from the token.
**Weak answer (what fails):** Applying STRIDE to the whole system in one sentence.
**Strong answer:** *Spoofing*: other workloads pretending to be Kong → mTLS + CN pin + NetworkPolicy.
*Tampering*: TLS integrity. *Repudiation*: correlation ID + Kong/WAF logs; app logs lack per-request
audit (gap) but ticket history records who did what. *Information disclosure*: TLS; error bodies
without stack traces. *DoS*: Kong limits, WAF limits; ticket-service has no own rate limit (relies on
gateway: acceptable only because NetworkPolicy blocks other callers). *Elevation*: the service
re-derives roles from the token; Kong cannot grant roles.

### Q3. Why pin the client certificate's CN when the CA is private and trusted? ★★★★
**30-second headline:** Chain validation alone accepts any workload certificate from the same CA; pinning the caller identity (better: URI SAN/SPIFFE) narrows it to Kong, and EKU clientAuth adds a second check.
**Weak answer (what fails):** "The CA is private, so any certificate is fine."
**Would I do it again?** Yes; but in production I'd pin a SPIFFE URI SAN rather than a CN.
**Strong answer:** The CA signs certificates for every component (WAF, Kong edge, Keycloak, service).
Chain validation alone would accept any of them as a client (if its EKU permits). Pinning the identity
(CN, better a URI SAN like `spiffe://cluster/ns/gateway/sa/kong`) reduces the allowed callers to one.
Also note EKU: only `kong-client` has `clientAuth`; Tomcat/JSSE enforce EKU when present: a second
layer.

### Q4. There is no revocation (CRL/OCSP) in this PKI. Is that acceptable? ★★★★★
**30-second headline:** No CRL/OCSP is acceptable only with short-lived certificates; 90 days is too long, so the compromise response is re-issue and reload, and the target is hours-long certs with automatic reload.
**Weak answer (what fails):** "We'd revoke it" with no revocation infrastructure.
**Would I do it again?** Partially: I'd shorten certificate lifetimes and automate reload before production.
**Strong answer:** Common and defensible for internal service identities **if certificates are
short-lived**: revocation is replaced by expiry. Here leaf certs live 90 days, which is too long to
rely on expiry after a key compromise. Today's response to compromise: delete the secret (re-issue with
a new key), `reload-certs.sh`, and if the CA key is compromised, rebuild the CA. Improvement: hours-
or days-long certs (service mesh / cert-manager with short duration + automatic reload), CA key in an
HSM or AWS Private CA.

### Q5. OWASP API Top 10: which three are most relevant to this system and how is each addressed? ★★★★
**30-second headline:** BOLA (ownership → 404, RLS), broken function-level authorisation (per-tenant roles, service checks), sensitive business flows (no self-approval); plus mass assignment prevented by DTO design.
**Weak answer (what fails):** Listing the Top 10 without mapping controls.
**Strong answer:** **API1 BOLA:** ownership check → 404, tenant RLS; **API5 broken function-level
authZ:** per-tenant roles with `@PreAuthorize` + service checks, approvers can't create, applicants
can't approve; **API6 sensitive business flows:** self-approval prevented (domain + DB constraint),
lock rules. Also API4 (limits) and API3 (body can't set `tenantId`/`email`: mass-assignment protection
by DTO design).
**Prove it:** e2e "tenant comes from security context, not the body".

### Q6. An attacker gets XSS on the UI. What can they do, and what limits them? ★★★★
**30-second headline:** XSS would let a script act as the user; CSP without inline script, textContent rendering, connect-src self and short tokens limit it; DPoP would make stolen tokens useless.
**Weak answer (what fails):** "We sanitise input" as the only control.
**Strong answer:** Injected script runs in the page: can call the API with the in-memory token as
the user, read data the user can read, exfiltrate the token (unless blocked) or the refresh token.
Limits: CSP `script-src 'self'` with no inline scripts makes injection hard in the first place
(stored ticket text is rendered with `textContent`); `connect-src 'self'` blocks exfiltration to other
origins via fetch/XHR (not via navigation tricks); short token lifetime; WAF blocks common XSS payloads
at input. DPoP would make stolen tokens useless elsewhere.

### Q7. Where can tokens leak in this architecture, and how is each mitigated? ★★★★
**30-second headline:** TLS-terminating proxies see tokens, so they don't log headers; tokens stay in memory in the browser; the code only travels in the URL with no-referrer.
**Weak answer (what fails):** Ignoring the proxies.
**Strong answer:** TLS-terminating proxies (WAF, Kong) see them → hardened, no header logging (WAF
audit parts AHZ; Kong access log has no headers). Browser storage → memory only. URLs → auth code only,
`no-referrer`. Application logs → not logged. Kubernetes → no tokens in env. Residual: process memory of
proxies; crash dumps.

### Q8. Supply chain: what's protected and what isn't? ★★★★
**30-second headline:** Now: actions pinned by SHA, dependency/secret/image scans gating CI, SBOM, keyless signing and signature verification before deploy; remaining: digest-pin all base images and admission verification in the cluster.
**Weak answer (what fails):** "We use official images."
**Since implemented:** CI/CD with scans, SBOM and cosign signing was implemented on 2026-10-05.
**Strong answer:** WAF image pinned by digest; Maven dependencies from Central (no lockfile/signature
verification); base images by tag (`eclipse-temurin:21-jre`, `nginx-unprivileged:1.27-alpine`,
`kong:3.9`, `keycloak:26.3`, `postgres:16`, `mongo:7`); cert-manager manifest fetched from GitHub
release URL at install. Improve: digests everywhere, Renovate for updates, SBOM (CycloneDX Maven plugin,
Syft), image scanning (Trivy/Grype) in CI with gates, cosign signatures + admission verification
(Kyverno), SLSA provenance for our images.

### Q9. Accepted gaps: how do you justify accepting "demo passwords in the repository" and "admin console reachable through the WAF" to a risk committee? ★★★★★
**30-second headline:** Risk acceptance needs scope, compensating controls, an owner and an expiry. Both gaps were then closed locally: credentials moved to OpenBao (random, rotated) and the admin console made internal-only at the WAF and Kong.
**Weak answer (what fails):** Calling it acceptable because "it's just a demo".
**Would I do it again?** Yes; closing them was cheaper than defending them.
**Since implemented:** both accepted gaps were closed on 2026-10-05.
**Strong answer:** Risk acceptance is a governance act, not a shrug: scope (laptop-only cluster,
synthetic data, no external reachability), likelihood (requires local access), impact (demo data only),
compensating controls (WAF inspects admin traffic; brute-force protection; NetworkPolicies),
**expiry/trigger** (must close before any shared/internet-facing environment), owner, and recorded
decision date (`docs/08` §8.8, 2026-10-05). The committee should see the remediation plan (Secrets
Manager; admin via internal ALB/VPN) costed and ready.

### Q10. Design security monitoring for this system: what signals, where, what alerts? ★★★★
**30-second headline:** Detections per layer (WAF rule families, 401/429 rates, workload-identity rejections, RLS fail-closed errors, Keycloak LOGIN_ERROR and admin changes, k8s exec/secrets) routed by severity, with dead-man's switch.
**Weak answer (what fails):** "Send all logs to the SIEM."
**Since implemented:** these detections now run locally (Loki rules → Alertmanager).
**Strong answer:** WAF audit rate and top rule IDs (attack campaigns, false positives after releases);
Kong 401/429 rates per route (credential stuffing, abuse); service 403 "workload identity" (lateral
movement attempt; should be zero); RLS-related SQL errors (fail-closed events); Keycloak `LOGIN_ERROR`
bursts and admin group changes; certificate expiry < 21 days; NetworkPolicy denies (CNI flow logs);
Kubernetes audit log for `exec`/`port-forward`/`get secrets`. Route to SIEM with correlation IDs.

### Q11. A pen-tester reports "mobile number and email returned to approvers". Is it a finding? ★★★
**30-second headline:** Not an access-control flaw but a minimisation question: confirm the business purpose, mask in lists, reveal on demand, keep it out of logs, record it in the RoPA.
**Weak answer (what fails):** Either dismissing it or removing the data without asking the business.
**Strong answer:** It's a data-minimisation question, not an access-control bug: approvers need
contact data to process tickets (business purpose). Verify purpose with the data owner, document in
the RoPA, mask in lists and reveal on demand if possible, and ensure logs don't contain it. Answering
with purpose limitation shows privacy-by-design thinking.

### Q12. Rapid fire
* What makes an ID token unusable at the API? → `typ` must be `Bearer`.
* Server name validation uses CN or SAN? → SAN (CN ignored by modern TLS clients for hostnames).
* What does `frame-ancestors 'none'` prevent? → clickjacking.
* Which hop is unencrypted? → Kong → UI (static files) and DB connections in-cluster (gaps).
* What blocks a rogue pod from calling the API? → NetworkPolicy (and mTLS if it got through).
