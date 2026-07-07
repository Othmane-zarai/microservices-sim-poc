#!/usr/bin/env bash
# Collect metrics from a running Online Boutique deployment for
# real-vs-simulator comparison. Produces three CSVs:
#   real-placement.csv   pod-to-node assignments (one row per pod)
#   real-hpa.csv         per-service desired/current replicas every 5 s
#   real-latency.csv     per-endpoint p50/p95/p99 from loadgenerator
#
# Does not depend on `jq` (uses kubectl jsonpath / go-templates only) so it
# works on Windows Git Bash and PowerShell environments.
set -euo pipefail

NS=${NS:-boutique}
DURATION=${DURATION:-600}
INTERVAL=${INTERVAL:-5}

if ! kubectl get namespace "${NS}" >/dev/null 2>&1; then
    echo "ERROR: namespace ${NS} not found." >&2
    exit 1
fi

echo "==> Snapshotting initial pod placement"
{
    echo "pod,namespace,node,phase,ip"
    kubectl get pods -n "${NS}" \
      -o go-template='{{range .items}}{{.metadata.name}},{{.metadata.namespace}},{{.spec.nodeName}},{{.status.phase}},{{.status.podIP}}{{"\n"}}{{end}}'
} > real-placement.csv
echo "    -> real-placement.csv ($(wc -l < real-placement.csv) rows)"

echo "==> Capturing HPA state every ${INTERVAL}s for ${DURATION}s"
{
    echo "timestamp,service,desired,current,target_cpu,observed_cpu"
    END=$(( $(date +%s) + DURATION ))
    while [ $(date +%s) -lt $END ]; do
        TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
        # go-template-based extraction is annoying for nested status; use
        # multiple `-o jsonpath` calls instead, which works without jq.
        for HPA in $(kubectl get hpa -n "${NS}" -o jsonpath='{.items[*].metadata.name}'); do
            DESIRED=$(kubectl get hpa "${HPA}" -n "${NS}" -o jsonpath='{.status.desiredReplicas}' 2>/dev/null || echo 0)
            CURRENT=$(kubectl get hpa "${HPA}" -n "${NS}" -o jsonpath='{.status.currentReplicas}' 2>/dev/null || echo 0)
            TARGET=$(kubectl get hpa "${HPA}" -n "${NS}" -o jsonpath='{.spec.metrics[0].resource.target.averageUtilization}' 2>/dev/null || echo 0)
            OBSERVED=$(kubectl get hpa "${HPA}" -n "${NS}" -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}' 2>/dev/null || echo 0)
            echo "${TS},${HPA},${DESIRED:-0},${CURRENT:-0},${TARGET:-0},${OBSERVED:-0}"
        done
        sleep "${INTERVAL}"
    done
} > real-hpa.csv
echo "    -> real-hpa.csv ($(wc -l < real-hpa.csv) rows)"

echo "==> Pulling loadgenerator latency stats"
LG_POD=$(kubectl get pods -n "${NS}" -l app=loadgenerator -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")
if [ -z "${LG_POD}" ]; then
    echo "WARNING: no loadgenerator pod found - skipping latency capture." >&2
else
    # Locust stdout layout: "<METHOD> <PATH> <reqs> <fails> <avg> <min> <max> <med> <reqs/s> <fails/s>".
    # We map MED->p50, MAX->p95-ish stand-in (actual p95/p99 require --csv export).
    kubectl logs -n "${NS}" "${LG_POD}" --tail=500 \
      | awk '
        BEGIN { print "method,path,p50_ms,p95_ms,p99_ms,rps" }
        /^GET|^POST|^PUT|^DELETE/ {
            method=$1; path=$2; reqs=$3;
            med=$(NF-2); max=$(NF-3); rps=$(NF-1);
            gsub(/[^0-9.]/, "", med); gsub(/[^0-9.]/, "", max); gsub(/[^0-9.]/, "", rps);
            if (med == "" || max == "") next;
            # Use median as p50; locust without --csv only gives med, avg, max.
            p50=med; p95=max; p99=max;
            printf "%s,%s,%s,%s,%s,%s\n", method, path, p50, p95, p99, rps
        }
      ' > real-latency.csv
    echo "    -> real-latency.csv ($(wc -l < real-latency.csv) rows)"
fi

echo "==> Final placement re-snapshot"
{
    echo "pod,namespace,node,phase,ip"
    kubectl get pods -n "${NS}" \
      -o go-template='{{range .items}}{{.metadata.name}},{{.metadata.namespace}},{{.spec.nodeName}},{{.status.phase}},{{.status.podIP}}{{"\n"}}{{end}}'
} > real-placement-end.csv

echo "==> Capture complete."
