# 5. mTLS and certificate rotation

## 5.1 The basics in plain words

| Term | Plain meaning |
|---|---|
| **TLS** | The encryption behind `https://`. It also proves *who* the server is. |
| **Certificate** | A small signed file saying "this public key belongs to *ticket-service*, valid until 2 Jan 2027". Public; safe to share. |
| **Private key** | The secret that matches the certificate. Whoever holds it *is* that identity. Never share or copy it. |
| **Certificate Authority (CA)** | The trusted signer of certificates. If you trust the CA, you trust every certificate it signed. |
| **Chain of trust** | certificate → signed by CA → which you trust. Verification walks this chain. |
| **SAN** (Subject Alternative Name) | The host names a certificate is valid for, e.g. `ticket-service.ticketing.svc.cluster.local`. |
| **Normal (one-way) TLS** | Only the server shows a certificate. The client stays anonymous (like your browser on a bank site). |
| **Mutual TLS (mTLS)** | **Both** sides show a certificate and verify the other's. Used between machines so each one knows exactly who is calling. |

## 5.2 Our private certificate authority

Inside the cluster we run our own CA with **cert-manager**, which creates and renews every certificate
automatically. Defined in `k8s/10-pki.yaml`:

```
selfsigned-bootstrap (ClusterIssuer)        only used once, to create the root
        │
        ▼
ticketing-root-ca   (Certificate, ECDSA P-256, valid 10 years, secret cert-manager/ticketing-root-ca)
        │  used by
        ▼
ticketing-ca        (ClusterIssuer)  signs all of these (RSA 2048, valid 90 days, renewed after 60):
   ├─ waf-edge        secret edge/waf-edge-tls             browser → WAF            (server)
   ├─ kong-edge       secret gateway/kong-edge-tls         WAF → Kong               (server)
   ├─ kong-client     secret gateway/kong-client-tls       Kong → ticket-service    (CLIENT identity, CN=kong-gateway)
   ├─ ticket-service  secret ticketing/ticket-service-tls  Kong → ticket-service    (server)
   ├─ keycloak        secret auth/keycloak-tls             Kong / ticket-service → Keycloak (server; also
   │                                                        names "localhost" for the admin tunnel, guide 1.2)
   └─ openbao         secret secrets/openbao-tls           External Secrets → OpenBao (server)
```

After a renewal, OpenBao and Keycloak pick up the new certificate on restart
(`kubectl -n secrets rollout restart statefulset/openbao`, `kubectl -n auth rollout restart deploy/keycloak`).

Each secret contains three files: `tls.crt` (the certificate), `tls.key` (the private key) and `ca.crt`
(the root CA certificate, used to verify the other side).

See the current state at any time:

```bash
kubectl get certificate -A -o custom-columns='NAMESPACE:.metadata.namespace,NAME:.metadata.name,READY:.status.conditions[0].status,EXPIRES:.status.notAfter,RENEWS:.status.renewalTime'
```

Because the browser does not know our private CA, it shows a warning for
`https://ticketing.localtest.me:8443`. That is expected on a laptop; on AWS a public certificate is used
for the browser-facing side instead (guide 7).

## 5.3 How mTLS works between Kong and ticket-service

ticket-service holds all business data, so it trusts **nobody** by default: not even the gateway in
front of it. Every API call proves three separate things:

```
Kong                                                         ticket-service (Tomcat, port 8443)
 │ 1. TCP connection (allowed only from the Kong pod by NetworkPolicy)
 │──────────────────────────────────────────────────────────────►│
 │ 2. TLS handshake                                              │
 │◄────────────── server certificate "ticket-service" ──────────│
 │   Kong checks: signed by ticketing-root-ca? name matches?     │
 │   valid dates? (tls_verify=true in Kong config)               │
 │─────────────── client certificate "kong-gateway" ───────────►│
 │                                    Tomcat checks: signed by ticketing-root-ca? valid dates?
 │                                    (server.ssl.client-auth=need → no certificate, no connection)
 │ 3. HTTP request with the user's JWT                           │
 │──────────────────────────────────────────────────────────────►│
 │                                    MtlsCallerFilter: certificate name (CN) is in the allow-list
 │                                    (only "kong-gateway")? else 403 "Caller workload identity is not allowed"
 │                                    Spring Security: JWT valid? else 401
```

Why step 3 matters: our CA signs certificates for several components. Checking only "signed by our CA"
would let *any* of them (e.g. Keycloak) call the API. The allow-list pins the caller to Kong.

Where it is configured:

| Side | Setting | File |
|---|---|---|
| ticket-service server certificate and trust | `server.ssl.bundle`, `client-auth: need`, files in `/tls` | `ticket-api/src/main/resources/application-k8s.yml` |
| ticket-service caller allow-list | `ticketing.mtls.allowed-callers: kong-gateway` | `ticket-api/src/main/resources/application.yml` |
| Kong client certificate + CA for upstream checks | `certificates`, `ca_certificates`, `client_certificate`, `tls_verify` | `scripts/render-kong.sh` |

The other internal hops use one-way TLS **with verification**: WAF → Kong (the WAF checks Kong's
certificate against the CA and the name `ticketing.localtest.me`), Kong → Keycloak and ticket-service →
Keycloak (both check Keycloak's certificate against the CA).

You can see mTLS refusing strangers (this is also part of `scripts/e2e.sh`):

```bash
kubectl -n ticketing port-forward deploy/ticket-service 18443:8443 &      # temporary tunnel
sleep 3
curl -sk https://localhost:18443/api/me; echo "exit code $?"              # no client certificate → TLS fails (exit code 56)
kill %1
```

## 5.4 Automatic renewal, and the step you must not forget

cert-manager renews each 90-day certificate **automatically after 60 days** (with a new private key)
and writes it into the secret. **But most components only read their certificate when they start**,
and Kong carries its client certificate inside its configuration. They keep using the previous
certificate until they are restarted. The previous certificate stays valid for another 30 days, so
nothing breaks immediately, but **if nobody acts, the service stops working when that old certificate
expires.**

What each component does with a renewed certificate (tested on this installation):

| Certificate | Used by | Picks up a renewal by itself? | Action |
|---|---|---|---|
| `waf-edge` | WAF | No | restart WAF |
| `kong-edge` | Kong | No | restart Kong |
| `kong-client` | Kong (embedded in its configuration) | No | `bash scripts/render-kong.sh` |
| `ticket-service` | ticket-service | No | restart ticket-service |
| `keycloak` | Keycloak | Yes, re-reads its files periodically (about hourly) | restart for immediate effect |
| `ticketing-root-ca` | everyone (trust) | n/a (10 years) | see 5.6 |

**One command does all of it:**

```bash
bash scripts/reload-certs.sh
bash scripts/e2e.sh          # must end with "0 failed"
```

`reload-certs.sh` waits until every certificate is ready, re-renders Kong's configuration, and restarts
ticket-service, the WAF and Keycloak one at a time (rolling restarts). Users may see a few seconds of
errors while a single-copy component restarts.

**Routine:** run it once a month, or whenever `kubectl get certificate -A` shows a recent renewal. Put
a reminder in the team calendar. (Recommended improvements that remove this manual step are listed in
5.8.)

## 5.5 Renewing a certificate on demand (rotation)

Rotate early if a private key may have leaked, or to practise. Example: the ticket-service certificate.

```bash
# 1. Note the current serial number (a unique ID of each certificate)
kubectl -n ticketing get secret ticket-service-tls -o jsonpath='{.data.tls\.crt}' | base64 -d | openssl x509 -noout -serial -enddate

# 2. Delete the secret: cert-manager immediately issues a brand-new certificate and key
kubectl -n ticketing delete secret ticket-service-tls
kubectl -n ticketing wait --for=condition=Ready certificate/ticket-service --timeout=90s

# 3. Check the serial changed
kubectl -n ticketing get secret ticket-service-tls -o jsonpath='{.data.tls\.crt}' | base64 -d | openssl x509 -noout -serial -enddate

# 4. Make the components use it, then test
bash scripts/reload-certs.sh
bash scripts/e2e.sh
```

Secret names for the other certificates are in the tree in 5.2 (`edge/waf-edge-tls`,
`gateway/kong-edge-tls`, `gateway/kong-client-tls`, `auth/keycloak-tls`). Deleting several at once and
running `reload-certs.sh` once is fine.

> Deleting the secret is the standard way to force re-issue without extra tools. (The optional
> `cmctl renew <name>` command from cert-manager does the same.) The running pods keep working with
> the old certificate files until they restart, so there is no outage between steps 2 and 4.

### Confirm what a component is actually serving

The secret may already be new while a pod still serves the old one. Compare the serials:

```bash
kubectl -n ticketing port-forward deploy/ticket-service 18443:8443 & sleep 3
openssl s_client -connect localhost:18443 </dev/null 2>/dev/null | openssl x509 -noout -serial -enddate
kill %1
```

(For the WAF use `kubectl -n edge port-forward deploy/waf 18444:8443` and add
`-servername ticketing.localtest.me`.) Guide 6 explains these `openssl` commands in detail.

## 5.6 Replacing the root CA (rare, advanced)

The root CA is valid for 10 years (until 2036 for this installation). Replacing it means every
component must trust the new CA **before** certificates signed by it are used. The safe procedure on a
laptop installation is simply to recreate the environment:

```bash
bash scripts/down.sh && bash scripts/up.sh
```

For an installation that cannot be recreated: (1) create a new CA Certificate and a second
ClusterIssuer, (2) add the new CA to every trust point (Kong `ca_certificates` in `render-kong.sh`, the
truststores of ticket-service and the WAF) so both CAs are trusted, (3) switch every Certificate's
`issuerRef` to the new issuer and run `reload-certs.sh`, (4) remove the old CA from the trust points.
Plan this as a change with a rollback.

## 5.7 Troubleshooting certificate problems

| Symptom | Likely cause | Check / fix |
|---|---|---|
| `/api` calls fail with **502** and Kong's log says `upstream SSL certificate verify error` | ticket-service certificate expired or not signed by the CA Kong trusts | `kubectl get certificate -A`; `bash scripts/reload-certs.sh` |
| `/api` calls fail with **403 "Caller workload identity is not allowed"** | ticket-service got a client certificate with a name other than `kong-gateway` | check `kong-client` certificate `commonName` in `k8s/10-pki.yaml`, re-run `render-kong.sh` |
| Every page fails with **502** from the WAF; its log says `upstream SSL certificate verify error` | Kong's edge certificate expired/invalid | `reload-certs.sh` |
| Certificate stays `READY False` | cert-manager problem | `kubectl -n <ns> describe certificate <name>` (events at the bottom); `kubectl -n cert-manager logs deploy/cert-manager` |
| Browser warning changed to "certificate expired" | WAF still serves an old certificate | `kubectl -n edge rollout restart deploy/waf` |

## 5.8 Recommended improvements

* Let Spring Boot reload its certificate without a restart:
  `spring.ssl.bundle.pem.server.reload-on-update: true` in `application-k8s.yml`.
* Have Kong read its client certificate from the mounted secret instead of embedding it, or run
  `reload-certs.sh` from a Kubernetes CronJob.
* Alert when any certificate is less than 21 days from expiry (Prometheus + cert-manager metrics).
* For larger setups, a service mesh (Istio, Linkerd) issues and rotates workload certificates
  automatically, every few hours.
