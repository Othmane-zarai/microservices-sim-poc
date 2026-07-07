#!/usr/bin/env bash
# Deploy GoogleCloudPlatform/microservices-demo (Online Boutique) to the
# current kubectl context, in namespace `boutique`. Pulls manifests
# pinned to v0.10.x (December 2024 release).
set -euo pipefail

NS=${NS:-boutique}
VERSION=${VERSION:-v0.10.1}
MANIFEST_URL="https://raw.githubusercontent.com/GoogleCloudPlatform/microservices-demo/${VERSION}/release/kubernetes-manifests.yaml"

echo "==> Creating namespace ${NS}"
kubectl create namespace "${NS}" --dry-run=client -o yaml | kubectl apply -f -

echo "==> Applying upstream manifests (${VERSION})"
kubectl apply -n "${NS}" -f "${MANIFEST_URL}"

echo "==> Applying HPA overlays (frontend / checkoutservice / recommendationservice)"
kubectl apply -n "${NS}" -f hpa.yaml

echo "==> Waiting for all pods to become Ready (timeout 240s)..."
kubectl wait --for=condition=ready pod --all -n "${NS}" --timeout=240s || {
    echo "WARNING: some pods are not Ready yet — continuing anyway."
    kubectl get pods -n "${NS}"
}

echo "==> Deployment complete. To capture metrics, run: bash collect-metrics.sh"
echo "==> To tear down: kubectl delete namespace ${NS}"
