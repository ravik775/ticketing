#!/usr/bin/env bash
# Creates a single-node k3d (k3s) cluster, builds the images, and deploys everything. Safe to re-run.
# Prerequisites: Docker, k3d, kubectl, JDK 21, openssl (Maven via ./mvnw).  Windows: run from Git Bash or WSL.
#
# Order matters:
#   cluster -> Kubernetes audit logging -> build -> cert-manager -> External Secrets -> PKI + OpenBao
#   -> secrets bootstrap (random credentials, never in Git) -> everything else -> gateway config
#   -> Keycloak audit events -> Keycloak MCP client
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=ticketing
CERT_MANAGER_VERSION=v1.18.2
ESO_MANIFEST=k8s/vendor/external-secrets-2.11.0.yaml

for c in docker k3d kubectl java openssl; do   # Maven comes from ./mvnw (pinned version)
  command -v "$c" >/dev/null || { echo "Missing prerequisite: $c" >&2; exit 1; }
done
docker info >/dev/null 2>&1 || { echo "Docker is not running" >&2; exit 1; }

retry() { for attempt in 1 2 3 4 5 6; do "$@" && return 0; [ "$attempt" = 6 ] && return 1; sleep 10; done; }

echo "==> Cluster"
if ! k3d cluster list 2>/dev/null | grep -q "^${CLUSTER} "; then
  # Host :8443 -> cluster load balancer :443 (the WAF). Traefik is disabled: the WAF is the only entry point.
  # API on 127.0.0.1: on Docker Desktop for Windows k3d otherwise writes host.docker.internal
  # (the LAN address) into the kubeconfig, which the host firewall commonly blocks.
  k3d cluster create "$CLUSTER" --servers 1 --agents 0 \
    --api-port 127.0.0.1:6550 \
    -p "8443:443@loadbalancer" \
    --k3s-arg "--disable=traefik@server:0" --wait
fi
kubectl config use-context "k3d-${CLUSTER}" >/dev/null

echo "==> Kubernetes API audit logging"
K3D_NODE="k3d-${CLUSTER}-server-0" bash scripts/enable-k8s-audit.sh

echo "==> Build (tests: run './mvnw verify' separately)"
./mvnw -q -B -DskipTests package
docker build -q -t ticketing/ticket-service:dev ticket-api
docker build -q -t ticketing/ui:dev ui
k3d image import -c "$CLUSTER" ticketing/ticket-service:dev ticketing/ui:dev

echo "==> cert-manager ${CERT_MANAGER_VERSION}"
kubectl apply -f "https://github.com/cert-manager/cert-manager/releases/download/${CERT_MANAGER_VERSION}/cert-manager.yaml"
kubectl -n cert-manager rollout status deploy/cert-manager deploy/cert-manager-webhook deploy/cert-manager-cainjector --timeout=240s

echo "==> External Secrets Operator (server-side apply: its CRDs exceed the client-side size limit)"
kubectl apply -f k8s/00-namespaces.yaml >/dev/null
kubectl apply --server-side -f "$ESO_MANIFEST" >/dev/null
kubectl -n external-secrets rollout status deploy/external-secrets --timeout=240s

echo "==> PKI and secrets manager (OpenBao)"
# the cert-manager webhook can need a few seconds after it reports ready
retry kubectl apply -f k8s/10-pki.yaml -f k8s/15-secrets-manager.yaml >/dev/null || { echo "apply failed" >&2; exit 1; }
kubectl wait --for=condition=Ready certificate --all -A --timeout=180s >/dev/null
bash scripts/secrets-bootstrap.sh

echo "==> Applying manifests"
retry kubectl apply -k k8s || { echo "kubectl apply failed" >&2; exit 1; }
bash scripts/secrets-bootstrap.sh --sync >/dev/null

echo "==> Gateway configuration"
bash scripts/render-kong.sh

echo "==> Keycloak audit events (ticketing + master realms)"
bash scripts/configure-keycloak-audit.sh

echo "==> Keycloak OAuth client for MCP clients (AI agents)"
bash scripts/configure-keycloak-mcp.sh

cat <<MSG

Ready:  https://ticketing.localtest.me:8443
        (self-signed certificate: accept the browser warning, or trust the CA - see README)
Users (password Passw0rd!):  alice / erin = applicants, bob / carol = approvers (acme), dave = approver (globex)
        alice is an applicant in BOTH acme and globex.
Admin:  kubectl -n auth port-forward svc/keycloak 9443:8443  ->  https://localhost:9443/auth/admin/  (internal only)
        Infrastructure credentials live in OpenBao; operator file: ~/.ticketing/openbao-init.json
Check:  scripts/e2e.sh   scripts/verify-observability.sh   scripts/restore-drill.sh
Grafana: kubectl -n observability port-forward svc/grafana 3000:3000  (user admin, password in secret observability/grafana-admin)
MSG
