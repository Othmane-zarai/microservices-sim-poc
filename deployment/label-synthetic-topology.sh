#!/usr/bin/env bash
# Apply synthetic rack/zone/region labels to the existing 3 real-cluster
# workers so RQ1 can exercise topology-spread predicates without
# provisioning a larger substrate (Option A in the SPE response).
#
# The labels are honoured by the real kube-scheduler regardless of the
# workers' physical reality, so this script lets a 3-worker cluster
# stand in as 3 zones × 3 racks for the purpose of evaluating
# nodeAffinity / podAffinity / topologySpreadConstraints decisions.
#
# This script is idempotent: re-running it leaves the cluster in the
# same state. The label keys match the conventions used by
# comparison-scripts/rq1_generate.py.
set -euo pipefail

CONTEXT=${CONTEXT:-$(kubectl config current-context)}
echo "==> Using kubectl context: ${CONTEXT}"

# Pre-flight: we expect exactly 3 worker nodes named worker-1..3 or
# k3s-worker-1..3 (the prefix k3s adds depends on installation).
mapfile -t NODES < <(kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | grep -E '(^|/)(k3s-)?worker-[1-3]$' | sort)
if [[ ${#NODES[@]} -ne 3 ]]; then
    echo "ERROR: expected 3 worker nodes named (k3s-)?worker-{1,2,3}; found:" >&2
    printf '  %s\n' "${NODES[@]}" >&2
    exit 1
fi
echo "==> Found 3 workers: ${NODES[*]}"

apply_labels() {
    local node=$1 role=$2 zone=$3 rack=$4
    echo "==> Labelling ${node} → role=${role}, zone=${zone}, rack=${rack}"
    kubectl label node "${node}" \
        --overwrite \
        "role=${role}" \
        "topology.kubernetes.io/zone=${zone}" \
        "topology.kubernetes.io/region=oci-1" \
        "rack=${rack}"
}

# Worker 1 → general / zone-a / rack r1
apply_labels "${NODES[0]}" general a r1

# Worker 2 → general / zone-b / rack r2
apply_labels "${NODES[1]}" general b r2

# Worker 3 → gpu / zone-c / rack r3, with dedicated=gpu:NoSchedule taint
apply_labels "${NODES[2]}" gpu c r3
echo "==> Tainting ${NODES[2]} with dedicated=gpu:NoSchedule"
kubectl taint node "${NODES[2]}" \
    dedicated=gpu:NoSchedule \
    --overwrite

echo
echo "==> Resulting labels:"
kubectl get nodes -L role,topology.kubernetes.io/zone,topology.kubernetes.io/region,rack
echo
echo "==> Resulting taints:"
kubectl get nodes -o custom-columns=NAME:.metadata.name,TAINTS:.spec.taints

echo
echo "Done. RQ1 scenarios that reference these labels can now be applied"
echo "via comparison-scripts/rq1_generate.py and run against this cluster."
echo
echo "To undo: kubectl label node <name> role- topology.kubernetes.io/zone- \\"
echo "         topology.kubernetes.io/region- rack- ; kubectl taint node <name> dedicated-"
