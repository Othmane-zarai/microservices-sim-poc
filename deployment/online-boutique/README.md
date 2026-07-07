# Online Boutique — Real-World Validation Track

This directory contains everything needed to validate `cloudsimplus-k8s`
against a real-world Kubernetes workload: Google Cloud's
[`microservices-demo`](https://github.com/GoogleCloudPlatform/microservices-demo)
(also known as **Online Boutique**). The same 10-microservice topology
is deployed on the OCI k3s cluster *and* simulated by
`K8sOnlineBoutiqueExample`; metrics from both are compared on placement,
HPA trajectory, and end-to-end request latency.

## Why Online Boutique?

- **Polyglot, production-grade**: 10 services across Go, Python, C#, Node.js,
  and Java. Resource patterns are heterogeneous, not synthetic.
- **Maintained by Google** as the canonical Kubernetes demo workload.
- **Self-contained**: bundled `loadgenerator` (Locust-based) produces a
  reproducible request stream without extra tooling.
- **Real-world fidelity**: every container has documented resource
  requests/limits, readiness probes, and inter-service call topology.

## Files

| File | Purpose |
|---|---|
| `README.md` | This document |
| `kubernetes-manifests.yaml` | Upstream manifests, pinned to `v0.10.x` |
| `hpa.yaml` | HPA definitions for the three elastic services (frontend, checkoutservice, recommendationservice) |
| `deploy.sh` | One-command deploy to the current `kubectl` context |
| `collect-metrics.sh` | Captures per-pod placement, HPA state, and Locust latency over a 10-minute window |
| `compare.py` | Compares real-cluster CSV outputs against the simulator's |

## Step-by-step run

### Real cluster

```bash
# 1. Confirm cluster context
kubectl config current-context           # → expect oci-k3s or similar

# 2. Deploy upstream manifests
bash deploy.sh

# 3. Wait for all pods Ready (≈ 90 s on the OCI substrate)
kubectl wait --for=condition=ready pod --all -n boutique --timeout=180s

# 4. Trigger the bundled loadgenerator (it's auto-deployed)
#    and capture metrics for 10 minutes
bash collect-metrics.sh

# 5. Tear down
kubectl delete namespace boutique
```

Output: `real-placement.csv`, `real-hpa.csv`, `real-latency.csv`.

### Simulator

```bash
cd ../..                       # back to microservices-sim-poc root
./mvnw.cmd package -DskipTests # -> target/cloudsimplus-k8s-examples-1.0.0-SNAPSHOT.jar
# Regenerate every sim-side artifact (moderate + high + high-recs) and rerun
# both comparison scripts with the pinned, deterministic config:
bash deployment/online-boutique/reproduce-sim.sh
```

`reproduce-sim.sh` runs `K8sOnlineBoutiqueExample` with the canonical flags
(`profile=moderate`/`high`/`high-recs`, `duration=600`, `tracesPerSec=10`,
`traceWarmupSeconds=0`) and emits `sim-placement.csv`, `sim-hpa.csv`,
`sim-latency.csv`, `sim-traces.json`, then `comparison-report.md` and
`trace-comparison.md`. The latency/trace draws are seeded, so results are
reproducible (only `sim-hpa.csv` timestamps vary; comparators align by index).
To run a single profile by hand:

```bash
java -Dk8s.duration=600 -Dboutique.profile=moderate \
  -Dk8s.nodeNames=k3s-server,k3s-worker-1,k3s-worker-2,k3s-worker-3 \
  -Dk8s.emitPlacementCsv=true -Dk8s.emitHpaCsv=true -Dk8s.emitLatencyCsv=true \
  -Dk8s.emitTracesJson=true -Dk8s.tracesPerSec=10 -Dk8s.traceWarmupSeconds=0 \
  -Dk8s.emitDir=deployment/online-boutique \
  -jar target/cloudsimplus-k8s-examples-1.0.0-SNAPSHOT.jar --example=K8sOnlineBoutiqueExample
```

### Comparison

```bash
cd deployment/online-boutique
python compare.py
```

Output: `comparison-report.md` with three sections:
- **Placement agreement**: per-service pod-to-node table; tied set
  agreement (post-A4 score-set metric).
- **HPA trajectory**: NRMSD of replica counts over time (frontend,
  checkoutservice, recommendationservice).
- **Latency**: sim vs real p50/p95/p99 per service; ratio table.

## Validation targets

| Dimension | Threshold | Notes |
|---|---|---|
| Placement agreement | ≥ 90% (winner) or 100% (score-set) | Six placements per service × ten services = 60 expected matches |
| HPA NRMSD per service | < 0.20 | Higher tolerance than RQ2 because the trajectory is multi-service |
| Latency ratio (sim/real, p95) | $< 0.5$ | The simulator under-estimates by design (no network jitter) |

## Known fidelity gaps

1. **Integer-PE quantisation (M3)**: a service requesting `200m` CPU
   consumes 1 PE in the simulator but 0.2 vCPU in real K8s. The
   example pre-empts this by declaring 4-PE workers; mapping is
   `1 PE = 250m` rather than `1 PE = 1000m`.
2. **gRPC vs simulator service-call latency**: the simulator's
   `ServiceCall` DAG has zero serialisation overhead; real Boutique
   uses gRPC with protobuf serialisation. Expect simulator p95 to
   under-estimate real p95 by 5–10 ms.
3. **Redis is modelled as a stateless container**: the simulator does
   not implement Redis's eviction or persistence semantics; the
   cart workload uses only ephemeral state, so this is not load-bearing.

## References

- Upstream: <https://github.com/GoogleCloudPlatform/microservices-demo>
- Architecture diagram: see `docs/img/architecture-diagram.png` in the
  upstream repo.
