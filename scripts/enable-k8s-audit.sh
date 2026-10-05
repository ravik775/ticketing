#!/usr/bin/env bash
# Enables Kubernetes API audit logging on the k3d (k3s) node: copies the policy into the node, adds the
# kube-apiserver flags as a k3s config drop-in, and restarts the node container (all pods restart; data
# on volumes is kept). Alloy then ships /var/log/kubernetes/audit/audit.log to Loki. Idempotent: does
# nothing if the same policy is already active.
# PRODUCTION (EKS): enable control-plane "audit" logs to CloudWatch and forward them to the SIEM.
#   Usage: scripts/enable-k8s-audit.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1      # Git Bash: keep container paths untouched
cd "$(dirname "$0")/.."
NODE="${K3D_NODE:-k3d-ticketing-server-0}"
POLICY=/var/lib/rancher/k3s/server/audit/audit-policy.yaml
DROPIN=/etc/rancher/k3s/config.yaml.d/audit.yaml
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
host() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }   # path for docker.exe

if docker exec "$NODE" sh -c "test -f $DROPIN && test -f $POLICY" 2>/dev/null; then
  docker cp "$NODE:$POLICY" "$(host "$WORK/current.yaml")" >/dev/null
  if cmp -s "$WORK/current.yaml" k8s/audit/audit-policy.yaml; then
    echo "Kubernetes audit logging already enabled on $NODE (policy unchanged)"
    exit 0
  fi
fi

docker exec "$NODE" mkdir -p /var/lib/rancher/k3s/server/audit /etc/rancher/k3s/config.yaml.d /var/log/kubernetes/audit
docker cp "$(host "$PWD/k8s/audit/audit-policy.yaml")" "$NODE:$POLICY"
cat > "$WORK/audit.yaml" <<'YAML'
kube-apiserver-arg:
  - "audit-policy-file=/var/lib/rancher/k3s/server/audit/audit-policy.yaml"
  - "audit-log-path=/var/log/kubernetes/audit/audit.log"
  - "audit-log-maxage=7"
  - "audit-log-maxbackup=2"
  - "audit-log-maxsize=20"
YAML
docker cp "$(host "$WORK/audit.yaml")" "$NODE:$DROPIN"

echo "==> Restarting $NODE to apply the API server audit flags (pods restart, volumes are kept)"
docker restart "$NODE" >/dev/null
# The k3d load balancer resolved the node's address at start; the restarted node may have a new one.
LB="${NODE%-server-0}-serverlb"
docker restart "$LB" >/dev/null 2>&1 || true
for i in $(seq 1 60); do kubectl get --raw /readyz >/dev/null 2>&1 && break; sleep 5; done
kubectl wait --for=condition=Ready node --all --timeout=180s >/dev/null
for i in $(seq 1 30); do docker exec "$NODE" sh -c 'test -s /var/log/kubernetes/audit/audit.log' && break; sleep 5; done
echo "audit log: $(docker exec "$NODE" sh -c 'wc -l < /var/log/kubernetes/audit/audit.log') events so far"
echo "==> Waiting for workloads to become ready again"
for ns in ticketing auth gateway edge observability; do
  kubectl -n "$ns" wait --for=condition=Ready pod --all --timeout=420s >/dev/null 2>&1 || echo "  (some pods in $ns still starting)"
done
echo "Kubernetes audit logging enabled."
