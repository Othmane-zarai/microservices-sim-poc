#!/usr/bin/env bash
# Regenerate ALL simulator-side Online Boutique artifacts deterministically and
# rerun the comparison scripts against the (fixed) real-side captures.
#
# This is the simulator half of the paired validation only — it does NOT touch
# the real-*.csv / real-traces.json captures (those require a live k3s cluster +
# Locust; see README.md and EMPIRICAL_VALIDATION.md §6.4). The M/M/c latency and
# trace draws are seeded, so sim-latency.csv and the per-operation trace
# percentiles are reproducible run-to-run (only sim-hpa.csv wall-clock
# timestamps differ; the comparators align by index, not timestamp).
#
# Pinned config (matches the committed artifacts):
#   duration=600s, tracesPerSec=10, traceWarmupSeconds=0, HPA target 0.70
#   moderate (USERS=200) -> top-level dir, nodeNames=k3s-server,k3s-worker-1..3
#   high     (USERS=500) -> runs/load500,      default worker-1..4 nodes
#   high-recs(USERS=500) -> runs/load500-recs,  default worker-1..4 nodes
#
# Usage:  bash deployment/online-boutique/reproduce-sim.sh [path/to/jar]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="${1:-$ROOT/target/cloudsimplus-k8s-examples-1.0.0-SNAPSHOT.jar}"
OB="$ROOT/deployment/online-boutique"

if [[ ! -f "$JAR" ]]; then
  echo "Jar not found: $JAR" >&2
  echo "Build it first:  ./mvnw.cmd -DskipTests package" >&2
  exit 1
fi

run() {  # run <profile> <emitDir> [extra -D flags...]
  local profile="$1" dir="$2"; shift 2
  echo ">>> profile=$profile -> $dir"
  java -Dk8s.duration=600 -Dboutique.profile="$profile" \
       -Dk8s.emitPlacementCsv=true -Dk8s.emitHpaCsv=true -Dk8s.emitLatencyCsv=true \
       -Dk8s.emitTracesJson=true -Dk8s.tracesPerSec=10 -Dk8s.traceWarmupSeconds=0 \
       -Dk8s.emitDir="$dir" "$@" \
       -jar "$JAR" --example=K8sOnlineBoutiqueExample | grep -E "Result:|Wrote.*traces" || true
  ( cd "$dir" && python "$OB/compare.py" >/dev/null 2>&1 && python "$OB/compare_traces.py" >/dev/null 2>&1 )
  echo "    comparison-report.md + trace-comparison.md regenerated in $dir"
}

# moderate (USERS=200): explicit real node names so placement winner-agreement is meaningful.
run moderate  "$OB"                  -Dk8s.nodeNames=k3s-server,k3s-worker-1,k3s-worker-2,k3s-worker-3
# high (USERS=500): default 4 worker nodes (matches the committed load500 substrate).
run high      "$OB/runs/load500"
# high-recs (USERS=500, recommendation-heavy Locust mix): not paper-cited, kept for completeness.
run high-recs "$OB/runs/load500-recs"

# Regenerate the combined per-operation figure used in the paper from the
# (hard-coded, auditable) log10 values inside plot_trace_comparison_combined.py.
python "$OB/plot_trace_comparison_combined.py"

echo "Done. Review with: git diff --stat deployment/online-boutique paper/figures"
