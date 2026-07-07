# RQ1 — kube-scheduler-simulator cross-tool comparison

This howto reproduces the **cross-tool RQ1 agreement** between
`cloudsimplus-k8s` and the upstream `kube-scheduler` binary, addressing
the reviewer's bonus question ("how does CSP-K8s compare to
kube-scheduler-simulator on the exact same RQ1 scenarios?").

The comparison runs entirely on the author's local machine — **no
real Kubernetes cluster is required**.

## Prerequisites

* Docker + Docker Compose installed locally.
* The RQ1 scenarios already generated under
  `deployment/rq1/real-manifests/` (300 YAML files produced by
  `comparison-scripts/rq1_generate.py`).
* The cloudsimplus-k8s simulator captures already produced under
  `deployment/rq1/sim-s*.log` by the cluster scenario CLI runner.

If either of the latter two is missing, run:

```bash
python comparison-scripts/rq1_generate.py --count 300 \
    --out-real deployment/rq1/real-manifests \
    --out-sim  deployment/rq1/scenarios \
    --seed 42

# Then run the simulator side as documented in deployment/REPRODUCIBILITY.md.
```

## Step 1 — Start kube-scheduler-simulator locally

```bash
git clone https://github.com/kubernetes-sigs/kube-scheduler-simulator.git
cd kube-scheduler-simulator
docker compose up -d
```

The web UI is reachable at <http://localhost:3000>; the REST API at
<http://localhost:1212>. Wait until both containers are healthy.

## Step 2 — Run the comparison harness

From the `microservices-sim-poc` repository root:

```bash
python comparison-scripts/rq1_kss_compare.py \
    --rq1-dir deployment/rq1 \
    --kss-endpoint http://localhost:1212 \
    --out-agreement deployment/rq1/kss-agreement.csv \
    --out-sensitivity deployment/rq1/kss-seed-sensitivity.csv \
    --seeds 1,2,3,5,8,13,21,34,55,89
```

The harness:

1. Wipes any existing pods/nodes on the kss instance.
2. Seeds the kss cluster with the same 3 worker nodes used by the real
   cluster captures (same labels, same taints).
3. Posts each `s###.yaml` real manifest to kss, records the assigned
   node, then deletes the pod before the next scenario.
4. Joins kss decisions with the cloudsimplus-k8s decisions parsed from
   `sim-s###.log`.

Two artefacts are produced:

* `kss-agreement.csv` — per-pod kss-vs-sim placement under the default
  (deterministic / `lexical()`) tie-break. Headline number.
* `kss-seed-sensitivity.csv` — the simulator side is re-run with 10
  different `random(seed)` tie-breaks, agreement vs kss is reported
  per seed, plus mean ± std at the bottom.

## Step 3 — Tear down

```bash
docker compose down
```

## What this comparison actually proves

`kube-scheduler-simulator` is a thin wrapper around the *production*
`kube-scheduler` binary; agreement against it is therefore a fidelity
claim against the exact code that runs in real Kubernetes clusters.
This is a **stronger fidelity argument than any real-cluster
comparison** because the real cluster's scheduler decisions are subject
to the same noise sources (node ordering, RNG state across restarts)
as kss, but without the substrate-cost barrier to running thousands of
scenarios.

The comparison **does not** validate:

* Latency or autoscaling timing (kss has no real workload).
* Cluster Autoscaler behaviour (kss only schedules existing pods).
* Real-cluster control-plane interactions (admission webhooks,
  scheduler restarts, leader election).

For those, the real-cluster captures in this directory remain the
primary evidence.
