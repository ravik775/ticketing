# 7. Hosting the application on AWS

This guide explains how each part of the platform would run on Amazon Web Services (AWS) and, in
detail, how to replace the self-signed laptop certificate with a **public, browser-trusted
certificate**. It is a design and runbook; it has not been executed against an AWS account, so treat
the commands as a starting point and review them with whoever owns the AWS account.

## 7.1 Target picture

```
                         ┌──────────── AWS account / one region, 3 Availability Zones ───────────────┐
 Users ── HTTPS 443 ──►  │ Route 53  tickets.example.com  (DNS)                                       │
                         │     │                                                                      │
                         │     ▼                                                                      │
                         │ Application Load Balancer (public subnets)                                 │
                         │   • public certificate from AWS Certificate Manager (ACM)                  │
                         │   • AWS WAF web ACL attached (managed rules + rate limit)                  │
                         │     │ HTTPS                                                                │
                         │     ▼                                                                      │
                         │ Amazon EKS cluster (private subnets, nodes in 3 AZs)                       │
                         │   edge ns:      ModSecurity WAF (optional 2nd layer)  x2                   │
                         │   gateway ns:   Kong (DB-less)                         x2+ ── ElastiCache  │
                         │   auth ns:      Keycloak                               x2   (Redis, for    │
                         │   ticketing ns: ticket-service                         x2+   rate limits)  │
                         │                 UI (or S3 + CloudFront)                                    │
                         │   cert-manager (internal mTLS certificates)                                │
                         │   External Secrets Operator ◄── AWS Secrets Manager                        │
                         │     │                         │                                            │
                         │     ▼                         ▼                                            │
                         │ Amazon RDS for PostgreSQL   Amazon DocumentDB / MongoDB Atlas              │
                         │ (Multi-AZ, isolated subnets) (isolated subnets, TLS)                       │
                         │                                                                            │
                         │ Amazon ECR (images) · CloudWatch (logs, metrics) · KMS (encryption keys)   │
                         └────────────────────────────────────────────────────────────────────────────┘
```

## 7.2 Why Amazon EKS

The platform is already a set of Kubernetes manifests. **Amazon EKS** (managed Kubernetes) runs them
with few changes, keeps the NetworkPolicies (the Amazon VPC CNI enforces them when its network-policy
feature is enabled) and keeps cert-manager for internal mTLS. Amazon ECS/Fargate would also work, but
every manifest, the network policies and the mTLS set-up would have to be rebuilt in ECS terms.

## 7.3 How each service is deployed

| Component | On AWS | Notes |
|---|---|---|
| **DNS** | Route 53 hosted zone for your domain | an alias record `tickets.example.com` → load balancer (or let *ExternalDNS* manage it) |
| **Public TLS + entry** | Application Load Balancer created by the **AWS Load Balancer Controller** from a Kubernetes `Ingress` | certificate from ACM (7.4); HTTP→HTTPS redirect; TLS 1.2+ policy |
| **Perimeter WAF** | **AWS WAF** web ACL attached to the ALB | managed rule groups + rate-based rule (7.5). Replaces or complements the in-cluster ModSecurity |
| **ModSecurity WAF** (`edge`) | EKS Deployment, 2 replicas, Service type **ClusterIP** | optional second layer; keeps the custom positive-model rules (allowed paths/methods/content type) |
| **Kong** (`gateway`) | EKS Deployment, 2+ replicas, HorizontalPodAutoscaler | rate-limit policy `redis` pointing to **ElastiCache for Redis** (TLS + auth), so all replicas share each user's quota |
| **Keycloak** (`auth`) | EKS Deployment, 2 replicas, `KC_CACHE=ispn` (clustered sessions) | database on RDS; admin console **not** public (7.7) |
| **UI** | EKS Deployment (as today), or static files in **S3 + CloudFront** | if CloudFront: copy the security headers from `ui/default.conf` into a CloudFront response-headers policy |
| **ticket-service** | EKS Deployment, 2+ replicas, HPA, PodDisruptionBudget | images from ECR; mTLS from Kong unchanged (cert-manager) |
| **PostgreSQL** | **Amazon RDS for PostgreSQL**, Multi-AZ, encrypted (KMS), automated backups + point-in-time recovery | Flyway migrations and Row-Level Security work unchanged; force TLS (`rds.force_ssl=1`) |
| **MongoDB** | **Amazon DocumentDB** (MongoDB-compatible) or **MongoDB Atlas** on AWS via PrivateLink | DocumentDB requires TLS and `retryWrites=false` in the connection string; run the integration tests against it before switching |
| **Internal certificates** | cert-manager (as today), or **AWS Private CA** with the `aws-privateca-issuer` plugin | Private CA adds audit and hardware-protected keys, at a monthly cost |
| **Secrets** | **AWS Secrets Manager** + **External Secrets Operator** | replaces the demo passwords in `k8s/kustomization.yaml` |
| **Images** | **Amazon ECR**, scan on push, immutable tags | deploy by digest, as already done for the WAF image |
| **Logs / metrics / traces** | CloudWatch Container Insights + Fluent Bit; Amazon Managed Prometheus/Grafana; AWS X-Ray or ADOT | see guide 2, 2.7 |

### Network layout

| Subnet tier (one per AZ) | Contains | Reachable from |
|---|---|---|
| Public | ALB, NAT gateways | the internet (ALB on 443 only) |
| Private | EKS worker nodes / pods | the ALB security group; NAT for outbound (Google sign-in, image pulls) |
| Isolated (no internet route) | RDS, DocumentDB, ElastiCache | only the EKS node/pod security groups, on their database ports |

Security groups replace "it is on the same laptop" with explicit rules, and the existing Kubernetes
NetworkPolicies keep working inside the cluster. Keycloak's egress rule for Google stays (port 443 to
public addresses, through the NAT gateway).

## 7.4 The public SSL certificate, step by step

On the laptop the browser sees a certificate from our private CA, hence the warning. On AWS the
browser-facing certificate comes from **AWS Certificate Manager (ACM)**: free, trusted by all
browsers, and **renewed automatically** by AWS.

### Step 1 – Own the domain and host it in Route 53

Buy a domain (in Route 53 or any registrar). In Route 53 → **Hosted zones** → create a public hosted
zone for `example.com`. If the domain was bought elsewhere, set the registrar's name servers to the
four NS records Route 53 shows.

### Step 2 – Request the certificate in ACM

1. Open **Certificate Manager** in the **same region as the load balancer**. (If you put CloudFront in
   front, the certificate must be in **us-east-1**.)
2. **Request** → *Request a public certificate*.
3. Domain name: `tickets.example.com` (add more names if needed; `*.example.com` covers one level of
   sub-domains).
4. Validation method: **DNS validation** (required for automatic renewal). Key algorithm: RSA 2048 (or
   ECDSA P-256).
5. Open the new certificate → **Create records in Route 53** → Create. AWS adds a CNAME record that
   proves you control the domain.
6. Within minutes the status becomes **Issued**. Copy its **ARN** (`arn:aws:acm:<region>:<account>:certificate/...`).

With the CLI:

```bash
aws acm request-certificate --domain-name tickets.example.com --validation-method DNS --region eu-west-1
aws acm describe-certificate --certificate-arn <arn> --query 'Certificate.DomainValidationOptions'   # the CNAME to create
aws acm describe-certificate --certificate-arn <arn> --query 'Certificate.Status'                   # wait for "ISSUED"
```

As long as the validation CNAME stays in DNS, ACM renews the certificate before it expires and the
load balancer picks up the new one by itself: **no rotation work for this certificate.**

### Step 3 – Attach it to the load balancer with an Ingress

Install the **AWS Load Balancer Controller** in the cluster (AWS documentation: "Install the AWS Load
Balancer Controller using Helm"). Then expose the entry service (the ModSecurity WAF, or Kong if you
drop it) with an Ingress instead of `type: LoadBalancer`:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: public
  namespace: edge
  annotations:
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTP":80},{"HTTPS":443}]'
    alb.ingress.kubernetes.io/ssl-redirect: "443"
    alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:eu-west-1:111122223333:certificate/REPLACE-ME
    alb.ingress.kubernetes.io/ssl-policy: ELBSecurityPolicy-TLS13-1-2-2021-06
    alb.ingress.kubernetes.io/backend-protocol: HTTPS          # re-encrypt from ALB to the pod
    alb.ingress.kubernetes.io/healthcheck-protocol: HTTPS
    alb.ingress.kubernetes.io/healthcheck-path: /healthz
    alb.ingress.kubernetes.io/wafv2-acl-arn: arn:aws:wafv2:eu-west-1:111122223333:regional/webacl/REPLACE-ME
spec:
  ingressClassName: alb
  rules:
    - host: tickets.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service: {name: waf, port: {number: 443}}
```

* The ALB **terminates** the public TLS with the ACM certificate and opens a **new** TLS connection to
  the pod (`backend-protocol: HTTPS`), so traffic stays encrypted inside the VPC. The pod keeps its
  cert-manager certificate. (The ALB does not validate the pod's certificate, so the security group and
  NetworkPolicy must ensure only the ALB can reach the pod.)
* Change the `waf` Service to `type: ClusterIP`. The ALB sends traffic straight to pod IPs.
* `ELBSecurityPolicy-TLS13-1-2-2021-06` allows only TLS 1.2 and 1.3.

### Step 4 – Point DNS at the load balancer

Route 53 → hosted zone → **Create record** → name `tickets`, type **A**, **Alias** → *Alias to
Application and Classic Load Balancer* → region → the ALB. (Or install *ExternalDNS*, which creates the
record from the Ingress `host` automatically.)

### Step 5 – Change the host name everywhere

The laptop name `ticketing.localtest.me:8443` is written in several places. Replace it with
`tickets.example.com` (port 443, so no `:8443`):

| File | Setting |
|---|---|
| `k8s/30-keycloak.yaml` | `KC_HOSTNAME=https://tickets.example.com/auth` (the token issuer) |
| `k8s/40-ticket-service.yaml` and `ticket-api/src/main/resources/application.yml` | `OIDC_ISSUER=https://tickets.example.com/auth/realms/ticketing` |
| `scripts/render-kong.sh` | `ISSUER=` (the JWT key name Kong matches) |
| `k8s/keycloak/realm-ticketing.json` | client `redirectUris`, `webOrigins`, `post.logout.redirect.uris` |
| `k8s/80-waf.yaml` | `server_name` and `proxy_ssl_name` |
| `k8s/10-pki.yaml` | `dnsNames` of `waf-edge` and `kong-edge` (internal certificates for the new name) |
| `scripts/e2e.sh`, `scripts/set-google.sh` | `BASE` URL |
| Google Cloud Console | OAuth client redirect URI `https://tickets.example.com/auth/realms/ticketing/broker/google/endpoint` |

The UI itself uses relative addresses and needs no change.

### Step 6 – Verify

```bash
openssl s_client -connect tickets.example.com:443 -servername tickets.example.com </dev/null 2>/dev/null | grep 'Verify return code'
# Verify return code: 0 (ok)     <- trusted without any -CAfile: it is a public certificate
curl -sI https://tickets.example.com/ | head -1                                   # HTTP/2 200, no warning
```

Then run `scripts/e2e.sh` with the new `BASE` (the cluster-internal checks need `kubectl` access to
the EKS cluster).

### Alternative: keep the certificate inside the cluster

If TLS must end inside the cluster (e.g. a Network Load Balancer in TCP pass-through mode), use
cert-manager with a **Let's Encrypt** `ClusterIssuer` and the **DNS-01** challenge through Route 53.
cert-manager then obtains and renews a public certificate into the `waf-edge-tls` secret, and
`scripts/reload-certs.sh` (guide 5) makes the WAF load it.

## 7.5 AWS WAF rules

Create a regional **web ACL** and attach it to the ALB (the `wafv2-acl-arn` annotation above). A good
baseline:

| Rule | Type | Action |
|---|---|---|
| `AWSManagedRulesAmazonIpReputationList` | managed | block known malicious IPs |
| `AWSManagedRulesCommonRuleSet` | managed | OWASP-style protections (XSS, LFI, bad inputs) |
| `AWSManagedRulesKnownBadInputsRuleSet` | managed | exploit patterns (e.g. Log4j) |
| `AWSManagedRulesSQLiRuleSet` | managed | SQL injection |
| Rate limit per IP | rate-based, e.g. 1000 requests / 5 min | block |
| Block `/auth/admin` and `/auth/realms/master` | custom (URI starts with) | block (7.7) |

Start new managed rules in **Count** mode for a few days, check the WAF logs for false positives, then
switch them to **Block**, the same approach as for ModSecurity (guide 9). Unlike the laptop, the real
client IP is available (ALB adds `X-Forwarded-For`), so per-IP rate limiting is meaningful; configure the
ModSecurity nginx to trust it (`set_real_ip_from <VPC CIDR>; real_ip_header X-Forwarded-For;`).

## 7.6 Databases on AWS

**RDS for PostgreSQL**

* Engine version 16, **Multi-AZ**, storage encrypted with KMS, automated backups (e.g. 14 days),
  deletion protection on, in the isolated subnets.
* Create the databases `ticketing` and `keycloak` and their users as in `k8s/20-postgres.yaml`.
  Strengthen tenant isolation by letting Flyway run as an *owner* user while the application connects
  as a separate user that **does not own** the tables (RLS is then impossible for it to switch off).
* Use TLS: JDBC URL
  `jdbc:postgresql://<endpoint>:5432/ticketing?sslmode=verify-full&sslrootcert=/rds/global-bundle.pem`
  (download the RDS CA bundle into a ConfigMap) and set `rds.force_ssl=1` in the parameter group.

**DocumentDB or MongoDB Atlas**

* DocumentDB: TLS is on by default; connection string
  `mongodb://ticketing_app:<pw>@<cluster-endpoint>:27017/ticketing?tls=true&tlsCAFile=/rds/global-bundle.pem&replicaSet=rs0&readPreference=secondaryPreferred&retryWrites=false&authSource=ticketing`.
  DocumentDB implements most but not all MongoDB features: run the integration tests against it.
* MongoDB Atlas: genuine MongoDB, connected privately through **AWS PrivateLink**.

Delete the in-cluster `postgres` and `mongo` StatefulSets and their NetworkPolicies; add egress rules
from ticket-service and Keycloak to the database subnets (NetworkPolicy `ipBlock`) plus the matching
security groups.

## 7.7 Security changes for an internet-facing deployment

* **Secrets:** move every password from `k8s/kustomization.yaml` into Secrets Manager, synchronised by
  External Secrets Operator (with EKS Pod Identity / IRSA giving each namespace access to only its own
  secrets). Generate new, long, random passwords.
* **Keycloak admin console:** do not expose `/auth/admin` or the `master` realm publicly. Block them in
  AWS WAF and reach the console through a separate **internal** ALB, a VPN, or `kubectl port-forward`.
* **Password grant:** remove `directAccessGrantsEnabled` from the `ticketing-ui` client (it exists only
  for `e2e.sh`). Run end-to-end tests with a dedicated test client in a test environment.
* **Encryption everywhere:** RDS/DocumentDB with TLS and KMS, EBS volumes encrypted, ElastiCache with
  in-transit encryption and AUTH.
* **Images:** ECR scan on push; deploy by digest; restrict nodes to pulling from your ECR.
* **Cluster access:** EKS API endpoint private (or restricted to admin IPs), access via IAM roles;
  audit logs enabled to CloudWatch.
* **Pod security:** enforce the Kubernetes *restricted* Pod Security Standard on the application
  namespaces (the manifests already comply except the WAF, which needs a writable root filesystem).

## 7.8 Availability, scaling and recovery

* 2+ replicas of Kong, Keycloak, ticket-service, WAF, spread across AZs
  (`topologySpreadConstraints`), each with a `PodDisruptionBudget` (`minAvailable: 1`).
* HorizontalPodAutoscalers on CPU for Kong and ticket-service.
* RDS automated backups + point-in-time recovery; DocumentDB/Atlas backups; test a restore every
  quarter.
* Keep everything as code (Terraform or CloudFormation for AWS resources; these manifests for EKS) so
  the whole environment can be rebuilt in another region.

## 7.9 Migration checklist

1. Create the AWS foundations: VPC (3 AZs, three subnet tiers), EKS cluster with managed node group,
   ECR repositories, KMS keys.
2. Create RDS PostgreSQL and DocumentDB/Atlas; store their credentials in Secrets Manager.
3. Install cluster add-ons: AWS Load Balancer Controller, VPC CNI network policies, cert-manager,
   External Secrets Operator, (ExternalDNS), metrics/log agents.
4. Push images to ECR (`docker tag` / `docker push`), update image references to ECR + digest.
5. Apply the manifests without the in-cluster databases; point the application at RDS/DocumentDB.
6. Change the host name (7.4 step 5), request the ACM certificate, create the Ingress and DNS record.
7. Create and attach the AWS WAF web ACL (Count mode first).
8. Run `scripts/render-kong.sh` against the EKS cluster, then `scripts/e2e.sh`.
9. Configure Google sign-in with the new redirect URI; give users roles (guide 1).
10. Monitor for a week (WAF counts, errors, latency), then switch WAF rules to Block.
