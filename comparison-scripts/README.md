# comparison-scripts/ — reproduce paper §8.2 numbers

Each script is the canonical reference for one of the four RQs in the
CloudSim Plus-K8s paper. Given the captured CSVs in
`deployment/rq{1,2,3,4}/` produced by following
`deployment/EMPIRICAL_VALIDATION.md`, these scripts reproduce every
quantitative number cited in `paper/kubernetes-cloudsimplus.tex` §8.2.

| Script | Input | Output | Paper number(s) |
|---|---|---|---|
| `rq1_generate.py` | `--count N` | 100 (`real-manifests/`, `scenarios/`) YAML pairs | RQ1 input set |
| `rq1_agreement.py` | `deployment/rq1/` dir | `summary.csv`, aggregate % to stdout | RQ1 agreement % |
| `rq2_nrmsd.py` | `deployment/rq2/` dir | `nrmsd.csv` | RQ2 pod-NRMSD, node MAE |
| `rq3_genyaml.py` | `--nodes N --pods P` | YAML to stdout | RQ3 input generator |
| `rq3_plot.py` | `scalability.csv` | log-log PDF + summary stats | RQ3 events/sec, peak heap |
| `rq4_extract.py` | sim log file | latency CSV to stdout | RQ4 sim-side input |
| `rq4_latency.py` | `real-latency.csv sim-latency.csv` | `latency-comparison.csv` | RQ4 p50/p95/p99 deltas |

## Reviewer-friendly reproducibility

A reviewer without Oracle access can reproduce **RQ1** entirely on a local
`kind` cluster:

```bash
kind create cluster --config kind-3worker.yaml   # 3 worker nodes
python comparison-scripts/rq1_generate.py --count 100 \
    --out-real /tmp/real --out-sim /tmp/sim
# ... follow EMPIRICAL_VALIDATION.md §7.1 with /tmp/real and /tmp/sim
```

RQ2 needs an HPA-capable cluster (any will do — kind or k3s); RQ3 is
laptop-only; RQ4 needs the same cluster as RQ2.

## Dependencies

```
python >= 3.10
pip install pandas numpy pyyaml matplotlib
```

## Determinism

`rq1_generate.py` uses `--seed` (default 42); regenerating with the same
seed produces byte-identical manifests. `rq3_genyaml.py` is also seeded.
Two runs of the same scenario on the same JVM produce byte-identical
placements (the K8s scheduler's E5 lexical tie-break is deterministic).
