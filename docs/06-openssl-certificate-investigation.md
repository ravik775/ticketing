# 6. Investigating certificates with OpenSSL

`openssl` is the standard tool for looking at certificates and testing TLS connections. It is already
installed in Git Bash (check with `openssl version`). This guide is a toolbox: each section answers
one question. Read [guide 5](05-mtls-and-certificate-rotation.md) first for the concepts (certificate,
key, CA, chain).

Throughout this guide `HOST=ticketing.localtest.me:8443` and `NAME=ticketing.localtest.me`. Set them
once per terminal:

```bash
HOST=ticketing.localtest.me:8443
NAME=ticketing.localtest.me
mkdir -p ~/certcheck && cd ~/certcheck      # a scratch folder for the files we download
```

> **Why `</dev/null` everywhere?** `openssl s_client` opens a connection and then waits for you to
> type. `</dev/null` tells it "nothing to send", so it prints the result and exits.
> **Why `-servername`?** It sends the host name in the TLS handshake (SNI); servers that host several
> names use it to choose the right certificate.

## 6.1 Which certificate does a server present?

```bash
openssl s_client -connect $HOST -servername $NAME </dev/null 2>/dev/null | openssl x509 -noout -subject -issuer -serial -dates -ext subjectAltName
```

Example output and how to read it:

```
subject=                                  <- empty: modern certificates put names in the SAN instead
issuer=CN=ticketing-root-ca               <- who signed it: our private CA
serial=969F322E5ACD5368E9F40977EC7AC662   <- unique number of THIS certificate (changes on renewal)
notBefore=Oct  5 06:08:29 2026 GMT        <- valid from
notAfter=Jan  3 06:08:29 2027 GMT         <- valid until (expiry)
X509v3 Subject Alternative Name: critical
    DNS:ticketing.localtest.me, DNS:localhost   <- the names it is valid for
```

Save it to a file to work with it further:

```bash
openssl s_client -connect $HOST -servername $NAME </dev/null 2>/dev/null | openssl x509 > server.pem
openssl x509 -in server.pem -noout -text          # everything: key type/size, algorithms, extensions
openssl x509 -in server.pem -noout -fingerprint -sha256   # a short unique "thumbprint" to compare certificates
```

## 6.2 When does it expire?

```bash
openssl x509 -in server.pem -noout -enddate
openssl x509 -in server.pem -noout -checkend 2592000 && echo "OK for 30 more days" || echo "EXPIRES WITHIN 30 DAYS"
```

`-checkend N` asks "is it still valid in N seconds?" (2592000 s = 30 days; 604800 s = 7 days). Its exit
code makes it perfect for scripts and monitoring.

Check every certificate stored in the cluster at once:

```bash
for s in edge/waf-edge-tls gateway/kong-edge-tls gateway/kong-client-tls ticketing/ticket-service-tls auth/keycloak-tls; do
  printf '%-32s ' "$s"
  kubectl -n "${s%/*}" get secret "${s#*/}" -o jsonpath='{.data.tls\.crt}' | base64 -d | openssl x509 -noout -enddate
done
```

## 6.3 Is the chain of trust valid?

A certificate is only trustworthy if it chains up to a CA you trust. Our certificates chain to our
**private** root CA, which no computer trusts by default, so first get that CA certificate:

```bash
kubectl -n edge get secret waf-edge-tls -o jsonpath='{.data.ca\.crt}' | base64 -d > ca.crt
openssl x509 -in ca.crt -noout -subject -issuer -dates -ext basicConstraints
```

`subject` = `issuer` = `ticketing-root-ca` (it signed itself: that is what a *root* is) and
`CA:TRUE` (it is allowed to sign other certificates).

Now verify, in two ways:

```bash
openssl verify -CAfile ca.crt server.pem
# server.pem: OK

openssl s_client -connect $HOST -servername $NAME -CAfile ca.crt -verify_hostname $NAME </dev/null 2>/dev/null | grep 'Verify return code'
# Verify return code: 0 (ok)
```

The second command checks the **live** connection: the chain *and* that the certificate is valid for
the name you expect.

### What the verify codes mean

| Code | Message | Meaning / typical cause |
|---|---|---|
| 0 | ok | everything checks out |
| 10 | certificate has expired | renew it (guide 5) |
| 18 | self-signed certificate | the server sent a certificate that signed itself and you do not trust it |
| 19 | self-signed certificate in certificate chain | the chain ends in a root you have not trusted (pass `-CAfile`) |
| 20 | unable to get local issuer certificate | you do not have the CA that signed it (pass `-CAfile`) |
| 21 | unable to verify the first certificate | the server sent only its own certificate and the issuer is unknown to you. **Normal for our servers without `-CAfile`**: they send only the leaf; give `-CAfile ca.crt` |
| 62 | hostname mismatch | the certificate is not valid for the name you connected to (wrong SAN / wrong server) |

### Seeing a full chain (public websites)

Public sites send their certificate plus one or more **intermediate** CAs. `-showcerts` lists them;
`s:` is the subject and `i:` is the issuer of each:

```bash
openssl s_client -connect github.com:443 -servername github.com -showcerts </dev/null 2>/dev/null | grep -E '^ *[0-9] s:|^ *i:'
#  0 s:CN=github.com                                     i:...Sectigo Public Server Authentication CA DV E36
#  1 s:...Sectigo Public Server Authentication CA DV E36  i:...Sectigo Public Server Authentication Root E46
#  2 s:...Sectigo Public Server Authentication Root E46   i:...USERTrust ECC Certification Authority
```

A correct chain is a ladder: each certificate's **issuer** is the **subject** of the next one. When you
install a public certificate (guide 7) you must install the *full chain* (your certificate followed
by the intermediates); a missing intermediate gives error 20/21 on many clients even though browsers
sometimes cope.

## 6.4 Does a private key belong to a certificate?

A mismatched key and certificate is a classic cause of "the server will not start". Compare a hash of
the public key inside each; the two lines must be identical:

```bash
kubectl -n edge get secret waf-edge-tls -o jsonpath='{.data.tls\.crt}' | base64 -d > waf.crt
kubectl -n edge get secret waf-edge-tls -o jsonpath='{.data.tls\.key}' | base64 -d > waf.key
openssl x509 -in waf.crt -noout -pubkey | openssl sha256
openssl pkey -in waf.key -pubout       | openssl sha256
rm -f waf.key        # never leave private keys lying around
```

## 6.5 Which TLS versions and ciphers are accepted?

```bash
openssl s_client -connect $HOST -servername $NAME -tls1_3 </dev/null 2>/dev/null | grep 'New, '   # New, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
openssl s_client -connect $HOST -servername $NAME -tls1_2 </dev/null 2>/dev/null | grep 'New, '   # New, TLSv1.2, Cipher is ECDHE-RSA-AES256-GCM-SHA384
openssl s_client -connect $HOST -servername $NAME -tls1_1 -cipher 'DEFAULT@SECLEVEL=0' </dev/null 2>&1 | grep -o 'alert protocol version'
# alert protocol version   <- the server REFUSES the outdated TLS 1.1 (good)
```

(Without `-cipher 'DEFAULT@SECLEVEL=0'` your own OpenSSL refuses to *offer* TLS 1.1 and prints
"no protocols available", which says nothing about the server.)

## 6.6 Checking internal services (inside the cluster)

Internal services are not reachable from your laptop. Open a temporary tunnel with `kubectl
port-forward`, check, then close it:

```bash
kubectl -n ticketing port-forward deploy/ticket-service 18443:8443 >/dev/null 2>&1 &
sleep 3
openssl s_client -connect localhost:18443 -CAfile ca.crt -verify_hostname ticket-service.ticketing.svc.cluster.local </dev/null 2>/dev/null \
  | grep -E 'Verify return code|Acceptable client certificate CA names' -A1
kill %1
```

Other internal endpoints: Kong `kubectl -n gateway port-forward deploy/kong 18445:8443` (name
`ticketing.localtest.me`), Keycloak `kubectl -n auth port-forward deploy/keycloak 18446:8443` (name
`keycloak.auth.svc.cluster.local`).

### Testing mTLS by hand (acting as Kong)

ticket-service demands a client certificate. Borrow Kong's to see the full mTLS exchange:

```bash
kubectl -n gateway get secret kong-client-tls -o jsonpath='{.data.tls\.crt}' | base64 -d > kong.crt
kubectl -n gateway get secret kong-client-tls -o jsonpath='{.data.tls\.key}' | base64 -d > kong.key
kubectl -n ticketing port-forward deploy/ticket-service 18443:8443 >/dev/null 2>&1 & sleep 3

# with the client certificate but no user token -> the TLS part succeeds, the app answers 401
printf 'GET /api/me HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' \
  | openssl s_client -quiet -connect localhost:18443 -cert kong.crt -key kong.key -CAfile ca.crt 2>/dev/null | head -1
# HTTP/1.1 401

kill %1; rm -f kong.key
```

Without `-cert/-key` the connection is refused during the handshake: that is mTLS doing its job.

> The `curl` that ships with Git for Windows cannot use these PEM key files for client certificates
> (it uses the Windows certificate store). Use `openssl s_client` as shown, as `scripts/e2e.sh` does.

## 6.7 Converting between formats

| You have | You need | Command |
|---|---|---|
| PEM (text, `-----BEGIN…`) | DER (binary) | `openssl x509 -in cert.pem -outform der -out cert.der` |
| DER | PEM | `openssl x509 -in cert.der -inform der -out cert.pem` |
| PEM cert + key | PFX/P12 (Windows, Java) | `openssl pkcs12 -export -in cert.pem -inkey key.pem -certfile chain.pem -out bundle.p12` |
| PFX/P12 | PEM | `openssl pkcs12 -in bundle.p12 -nodes -out all.pem` |
| Something unknown | to see what it is | `openssl x509 -in file -noout -text` (or `-inform der`); for keys `openssl pkey -in file -noout -text` |

In Git Bash, a `-subj` argument must start with two slashes, e.g. `-subj "//CN=test"` (Git Bash would
otherwise turn `/CN=test` into a Windows path).

## 6.8 Investigation checklist

When "something is wrong with TLS":

1. **Which certificate is served?** 6.1: is it the one you expect (serial, names, issuer)?
2. **Expired?** 6.2.
3. **Chain valid and name right?** 6.3 with `-CAfile` and `-verify_hostname` → code 0?
4. **Key matches?** 6.4 (if a component fails to start after a change).
5. **Protocol mismatch?** 6.5 (old clients that only speak TLS 1.0/1.1 are refused on purpose).
6. **Secret renewed but pod still serves the old one?** compare 6.1 (live) with 6.2 (secret) →
   `bash scripts/reload-certs.sh`.

Clean up afterwards: `rm -rf ~/certcheck`.
