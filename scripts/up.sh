#!/usr/bin/env bash
# Creates a single-node k3d (k3s) cluster, builds the images, and deploys everything.
# Prerequisites: Docker, k3d, kubectl, JDK 21, Maven.  Windows: run from Git Bash or WSL.
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=ticketing
CERT_MANAGER_VERSION=v1.18.2

for c in docker k3d kubectl mvn java; do
  command -v "$c" >/dev/null || { echo "Missing prerequisite: $c" >&2; exit 1; }
done
docker info >/dev/null 2>&1 || { echo "Docker is not running" >&2; exit 1; }

echo "==> Cluster"
if ! k3d cluster list 2>/dev/null | grep -q "^${CLUSTER} "; then
  # Host :8443 -> cluster load balancer :443 (Kong). Traefik is disabled: Kong is the only entry point.
  # API on 127.0.0.1: on Docker Desktop for Windows k3d otherwise writes host.docker.internal
  # (the LAN address) into the kubeconfig, which the host firewall commonly blocks.
  k3d cluster create "$CLUSTER" --servers 1 --agents 0 \
    --api-port 127.0.0.1:6550 \
    -p "8443:443@loadbalancer" \
    --k3s-arg "--disable=traefik@server:0" --wait
fi
kubectl config use-context "k3d-${CLUSTER}" >/dev/null

echo "==> Build (tests: run 'mvn verify' separately)"
mvn -q -DskipTests package
docker build -q -t ticketing/ticket-service:dev ticket-api
docker build -q -t ticketing/ui:dev ui
k3d image import -c "$CLUSTER" ticketing/ticket-service:dev ticketing/ui:dev

echo "==> cert-manager ${CERT_MANAGER_VERSION}"
kubectl apply -f "https://github.com/cert-manager/cert-manager/releases/download/${CERT_MANAGER_VERSION}/cert-manager.yaml"
kubectl -n cert-manager rollout status deploy/cert-manager deploy/cert-manager-webhook deploy/cert-manager-cainjector --timeout=240s

echo "==> Applying manifests"
for attempt in 1 2 3 4 5 6; do   # the cert-manager webhook can need a few seconds after it reports ready
  kubectl apply -k k8s && break
  [ "$attempt" = 6 ] && { echo "kubectl apply failed" >&2; exit 1; }
  sleep 10
done

echo "==> Gateway configuration"
bash scripts/render-kong.sh

cat <<MSG

Ready:  https://ticketing.localtest.me:8443
        (self-signed certificate: accept the browser warning, or trust the CA - see README)
Users (password Passw0rd!):  alice / erin = applicants, bob / carol = approvers (acme), dave = approver (globex)
        alice is an applicant in BOTH acme and globex.
Check:  scripts/e2e.sh
MSG
