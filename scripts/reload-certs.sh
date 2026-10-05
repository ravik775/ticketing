#!/usr/bin/env bash
# Makes every component use the CURRENT certificates from cert-manager.
#
# cert-manager renews certificates automatically (every 60 days for 90-day certificates), but most
# components read their certificate only at start-up, and Kong has its client certificate embedded in
# its configuration. Until this script runs they keep using the previous certificate, which stops
# working when it expires (30 days after the renewal). Run it after any renewal, or monthly.
# Safe to run at any time; each component restarts with a rolling update.
#   Usage: scripts/reload-certs.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> Certificates (all must be Ready)"
kubectl get certificate -A
kubectl wait --for=condition=Ready certificate --all -A --timeout=180s >/dev/null

echo "==> Kong: re-embed its client certificate and CA, restart (also reloads its own TLS certificate)"
bash scripts/render-kong.sh

for target in "ticketing ticket-service" "edge waf" "auth keycloak"; do
  set -- $target
  echo "==> Restarting $2 (namespace $1) so it loads its renewed certificate"
  kubectl -n "$1" rollout restart "deploy/$2"
done
for target in "ticketing ticket-service" "edge waf" "auth keycloak"; do
  set -- $target
  kubectl -n "$1" rollout status "deploy/$2" --timeout=420s
done

echo
echo "Done. Verify with:  bash scripts/e2e.sh   (and see docs/05-mtls-and-certificate-rotation.md)"
