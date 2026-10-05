# Enterprise integration: customer IdP federation, SCIM, MFA, BYOK/KMS, webhooks, incident response

> **Not implemented locally.** These are the questions enterprise customers and their security teams ask
> in every SaaS RFP. The local system has none of them yet; answers are proposals built on the existing
> design (Keycloak, Kong, OpenBao, the outbox), each requiring architecture approval.

## Concepts you must own

* **Identity federation per tenant:** each customer signs in with *their* IdP (Entra ID, Okta, Ping) over
  SAML 2.0 or OIDC; the SaaS IdP brokers it. Home-realm discovery picks the right IdP (email domain,
  tenant-specific login URL). Claims mapping turns the customer's groups into this app's `/tenant/role`.
* **SCIM 2.0 (RFC 7643/7644):** the customer's IdP pushes user create/update/deactivate to the SaaS, so
  leavers lose access without a ticket (joiner-mover-leaver).
* **MFA and step-up:** authentication strength (`acr`/`amr` claims, ACR values in Keycloak); require a
  stronger factor for sensitive actions (approving) rather than for every login.
* **Encryption keys:** encryption at rest everywhere; **BYOK/HYOK**: per-tenant data keys wrapped by a
  customer-controlled KMS key (envelope encryption); revoking the key crypto-shreds the tenant's data.
* **Integrations:** outbound **webhooks** (signed, retried, idempotent, per-tenant secrets, SSRF-safe),
  inbound APIs for automation (OAuth client credentials, scoped tokens, per-client quotas).
* **Incident response (NIST SP 800-61):** preparation, detection & analysis, containment, eradication,
  recovery, post-incident; plus regulatory clocks (GDPR 72 h to the authority; customer contracts often 24–72 h).

## How this application would apply them (proposal)

| Capability | Built on | Design sketch |
|---|---|---|
| Tenant IdP federation | Keycloak identity brokering (already used for Google) | one IdP per tenant (or Keycloak Organizations), IdP mapper sets the tenant group; `kc_idp_hint` from a tenant login URL |
| SCIM | Keycloak SCIM extension or a small SCIM service writing via the Keycloak admin API | deactivate = disable user + revoke sessions; group sync maps to `/tenant/role` |
| MFA / step-up | Keycloak authentication flows + conditional OTP/WebAuthn; ACR | approve/reject requires `acr >= 2`; the service checks the claim (like `typ`/`azp` today) |
| BYOK | OpenBao Transit (local), AWS KMS (production) | per-tenant data key encrypts `description` and `mobile` before storage; key per tenant in the customer's KMS account |
| Webhooks | the outbox (events are already durable and ordered) | a dispatcher reads published events, signs with HMAC per tenant secret, retries with backoff, dead-letters, exposes delivery logs |
| Incident response | the SIEM, audit trail, restore drill and secret rotation built locally | runbooks per scenario, severity matrix, comms templates, evidence preservation |

## Local (narrowed) → Production (unwrapped)

| Q | Aspect | Local (narrowed) | Production (unwrapped) | Validate in production |
|---|---|---|---|---|
| Q1 | Federation | one realm, local users + Google | per-tenant IdPs (SAML/OIDC) with domain-based discovery | per-tenant login test in onboarding checklist |
| Q2 | SCIM | manual `add-role.sh` | SCIM endpoint per tenant with bearer token | leaver test: deactivated in IdP → no access within minutes |
| Q3 | MFA | none (password grant even enabled for tests) | WebAuthn/OTP, step-up for approvals, password grant off | token `acr` checked by API; pen-test |
| Q4 | Keys | etcd/volume not encrypted, TLS everywhere | KMS envelope encryption, per-tenant keys for BYOK customers | key revocation drill renders data unreadable |
| Q5 | Webhooks | none | outbox-driven dispatcher, signed, retried | contract tests with customer endpoints; delivery SLO |
| Q6–Q7 | Incident response | runbooks in `docs/`, SIEM rules | on-call, IR plan, tabletop exercises, regulator/customer notification process | quarterly tabletop; MTTD/MTTR metrics |

## Prove it (what exists today that these build on)

```bash
kubectl -n auth exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh get identity-provider/instances -r ticketing --fields alias,providerId --config /tmp/kcadm.config  # brokering already in use (Google)
kubectl -n ticketing exec postgres-0 -c postgres -- psql -U postgres -d ticketing -c "select event_type, count(*) from ticket_outbox group by 1"  # durable event stream for webhooks
kubectl -n secrets get pods                                                        # OpenBao: Transit engine is the local BYOK building block
```

---

## Questions

### Q1. A bank wants its employees to sign in with their own Entra ID, and a second customer uses Okta. Design it. ★★★★
**30-second headline:** Keycloak brokers one IdP per tenant (OIDC or SAML), discovery by email domain or tenant login URL, and an IdP mapper assigns the tenant group, so the API and its token checks stay unchanged.
**Weak answer (what fails):** "Each customer gets their own Keycloak" or trusting any IdP's email to identify the tenant.
**Strong answer:** Register each customer IdP in the realm (or as a Keycloak Organization with its IdP).
Home-realm discovery: `kc_idp_hint` from a tenant-specific URL or the email domain. Map the customer's
groups/roles to `/<tenant>/<role>` with IdP mappers, *forced* on login so the customer controls access.
Trust boundary: an IdP may only assert membership of **its own** tenant (mapper hard-codes the tenant
prefix), otherwise a malicious customer IdP could claim another tenant. Key users on IdP + `sub`, not
email. Certificates/metadata rotation per IdP is an operational duty; monitor IdP login failures per tenant.
**Follow-ups / traps:** "What if the bank's IdP is down?" (their users can't log in; existing tokens work
≤ 5 min; no fallback to local passwords unless contractually agreed).

### Q2. How do leavers lose access, and how fast? ★★★★
**30-second headline:** SCIM deprovisioning from the customer's IdP disables the user and revokes sessions; remaining exposure is the access-token lifetime (≤ 300 s), stated in the contract.
**Weak answer (what fails):** "The customer emails us."
**Strong answer:** SCIM `PATCH active=false` → disable in Keycloak, revoke sessions (refresh fails),
remove group memberships; access tokens already issued expire within 5 minutes because validation is
local. For immediate cut-off on high-risk tenants: shorter token life or a revocation list at the gateway.
Audit every SCIM change (admin events → SIEM, already flowing) and reconcile nightly against the IdP.

### Q3. The customer's CISO requires MFA for approvers but not for applicants. ★★★★
**30-second headline:** Step-up, not blanket MFA: approval endpoints require a token with a stronger authentication context (`acr`), obtained by re-authenticating with WebAuthn/OTP; the service enforces it like it enforces `typ` and `azp`.
**Weak answer (what fails):** "Turn on MFA in Keycloak" without enforcement in the API.
**Strong answer:** Keycloak conditional flows map ACR levels to factors; the UI requests `acr_values=2`
before approving; the token carries `acr`; the API validator rejects approver actions with lower `acr`
(403 with a step-up hint). If the customer federates, their IdP's MFA claim (`amr`) must be mapped and
trusted per tenant. Disable the password grant (it bypasses all of this).

### Q4. "We require BYOK." What exactly do you offer, and what does it cost you? ★★★★★
**30-second headline:** Envelope encryption: each tenant's sensitive fields are encrypted with a data key wrapped by a key in the customer's KMS; revoking that key makes their data unreadable. Costs: no server-side search on those fields, KMS latency and availability coupling, operational runbooks.
**Weak answer (what fails):** "The disks are encrypted" (that is the provider's key, not the customer's).
**Strong answer:** Application-level envelope encryption for `description`/`mobile` (and the outbox copy):
per-tenant data encryption key, wrapped by the tenant's CMK (AWS KMS cross-account grant, or OpenBao
Transit locally); cache unwrapped keys briefly in memory; rotate data keys; revocation = crypto-shredding.
Consequences: encrypted fields can't be indexed/searched or used by AI without decryption; backups
remain encrypted (good for erasure); a customer revoking their key causes errors for their tenant only
(design graceful failure). Offer it as an enterprise-tier feature; it pairs with silo/cell placement.
**Would I do it again?** I'd design the data model for field-level encryption from the start; retrofitting it touches every query and the outbox.

### Q5. Customers want to be notified in their own systems when a ticket is approved. Design webhooks. ★★★★
**30-second headline:** Drive webhooks from the outbox: a dispatcher sends each published event to the tenant's endpoint, HMAC-signed with a timestamp, retried with backoff, idempotent by event ID, dead-lettered after N tries, with delivery logs per tenant.
**Weak answer (what fails):** Calling customer URLs synchronously inside the approval request.
**Strong answer:** Events are already durable and ordered in `ticket_outbox`. Dispatcher (separate
deployment) reads them, POSTs JSON with `X-Signature: t=…,v1=HMAC(secret, t.body)`; customer verifies and
rejects old timestamps (replay). Retries with exponential backoff + jitter, circuit breaker per endpoint,
per-tenant concurrency limits, dead-letter queue and a redelivery API. Security: SSRF protection (block
private IP ranges, resolve-then-connect checks), HTTPS only, secrets in OpenBao per tenant, egress through
a controlled NAT/proxy (NetworkPolicy today allows no such egress, by design).

### Q6. 02:00, the SIEM fires `UntrustedWorkloadIdentity` and `KubernetesSecretsReadByHuman` together. Walk me through the first hour. ★★★★★
**30-second headline:** Treat as a possible intrusion: declare an incident, preserve evidence, contain (isolate the source workload, revoke credentials, rotate secrets), assess impact from the audit trail, recover, and communicate on the legal clock.
**Weak answer (what fails):** Restarting pods immediately (destroys evidence) or waiting until morning.
**Strong answer:** Triage (5 min): which identity read which secret, from where (Kubernetes audit: user,
source IP, objectRef), which caller hit ticket-service with a bad certificate (service log + WAF/Kong
correlation). Declare a SEV, open a channel, assign incident commander. Preserve: export relevant Loki
streams and audit logs, snapshot the suspicious pod (`kubectl debug`/checkpoint), don't delete yet.
Contain: NetworkPolicy deny-all on the source namespace, revoke the user's kubeconfig/RBAC, rotate every
secret it could read (`scripts/secrets-bootstrap.sh --rotate` exists for exactly this), reissue
certificates if keys were exposed. Assess: what data could have been accessed (RLS limits per tenant;
the audit trail says which). Recover and monitor. Notify: privacy/legal decide on GDPR 72-hour
notification and customer contractual clocks. Afterwards: blameless post-incident review and control
improvements.

### Q7. How do you prepare for incidents before they happen? ★★★★
**30-second headline:** An incident response plan with roles and severity matrix, tested runbooks (rotation, restore, isolation), detections with owners, evidence retention, communication templates, and quarterly tabletop exercises measured by MTTD/MTTR.
**Weak answer (what fails):** "We have monitoring."
**Strong answer:** NIST 800-61 preparation: on-call with escalation, a severity matrix tied to customer
impact and data exposure, runbooks that have actually been executed (this repo has a restore drill and a
credential rotation that were run for real), detections tested by synthetic attacks (the master-realm
alert was proven to fire), log retention long enough for investigations, pre-approved containment
actions, legal/privacy contacts, customer comms templates, tabletop exercises with lessons tracked to
closure.

### Q8. Rapid fire
* Protocol for pushing user changes from a customer IdP? → SCIM 2.0.
* Claim that tells the API how strongly the user authenticated? → `acr` (and `amr`).
* What makes BYOK meaningful? → the customer controls (and can revoke) the key that wraps your data keys.
* Where would webhooks get their events? → the transactional outbox.
* GDPR breach notification deadline to the authority? → 72 hours after becoming aware.
