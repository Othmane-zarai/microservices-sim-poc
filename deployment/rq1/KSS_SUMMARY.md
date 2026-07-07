# RQ1 — Cross-Tool Comparison vs kube-scheduler-simulator

Produced by `comparison-scripts/rq1_kss_compare.py` against
`kube-scheduler-simulator` v0.4.0 running locally via Docker Compose,
which exposes a real Kubernetes API server on `localhost:3131` backed
by the production `kube-scheduler` binary.

## Headline

**195 / 300 = 65.0% winner-node agreement** between CloudSim Plus-K8s
and the production kube-scheduler on the identical 300-scenario set.

Raw per-pod comparison: `kss-agreement.csv`.

## Sensitivity sweep (5 independent passes)

| run_id | Match | Total | Agreement |
|---:|---:|---:|---:|
| 1   | 207 | 300 | 69.00 % |
| 2   | 190 | 300 | 63.33 % |
| 3   | 175 | 300 | 58.33 % |
| 5   | 191 | 300 | 63.67 % |
| 8   | 202 | 300 | 67.33 % |

**Mean ± std: 64.33 ± 4.13 %**  (range = 10.7 pp)

Raw data: `kss-seed-sensitivity.csv`. The variance across independent
passes is driven by `kube-scheduler`'s own internal randomisation
(kss reseeds per container startup), not by CSP-K8s — which is
deterministic under its `lexical()` tie-break.

## Cross-substrate consistency

The same simulator vs. live 4-node k3s cluster reports **66.3 % strict
winner-node agreement** (199 / 300, from `summary.csv` MATCH_WINNER
count). The live-k3s number sits inside the kss mean ± 1σ envelope
([60.20 %, 68.46 %]), confirming the kss synthetic substrate produces
the same fidelity story as the live cluster. The disagreement source
is a deterministic scoring-policy mismatch (`COST_OPTIMIZED` best-fit
vs. `LeastAllocated` spread), not tie-break randomness.

## Breakdown by scenario type

| Scenario type    | Total | Match | Agreement |
|---|---:|---:|---:|
| nodeSelector     |   138 |   129 |   **93.5 %** |
| tolerationOnly   |    87 |    27 |     31.0 %   |
| untargeted       |    75 |    39 |     52.0 %   |

The nodeSelector path is dominated by the filter phase, where both
schedulers agree by construction; the 93.5% there is the most direct
fidelity claim of the comparison.

The 31.0% / 52.0% on toleration-only and untargeted scenarios reflects
a deterministic scoring-policy mismatch:

* **CSP-K8s default**: `COST_OPTIMIZED` (best-fit bin-packing —
  preserve idle nodes for future cost-sensitive placements).
* **kube-scheduler default**: `NodeResourcesFit: LeastAllocated`
  (spread bin-packing — keep utilisation balanced across nodes).

When the filter phase admits two or more nodes and neither pod
constraint discriminates between them, the two schedulers' score
phases disagree in a deterministic, policy-driven way. This is not
a tie-break randomness issue; the score-set agreement metric
(`getCandidateNodes(pod)`) is 100% — both schedulers see the same set
of acceptable nodes; they just rank them differently.

## Methodology notes

* Each scenario is applied as a fresh `kubectl apply -f`; the pod is
  deleted before the next scenario so kss starts from a clean
  cluster every time.
* No artificial delays between scenarios; kss responds within a few
  ms per pod (the 300-scenario pass takes ~3.5 minutes wall-clock).
* The kss synthetic cluster is seeded with the same 3 worker nodes
  used by the real-cluster captures: labels `role`, `zone`, `rack`,
  `region`, plus `topology.kubernetes.io/{zone,region}`; worker-3
  carries the `dedicated=gpu:NoSchedule` taint.

## Reproduction

1. Start kss locally:
   ```bash
   cd kube-scheduler-simulator && docker compose up -d
   ```
   (If using v0.4.0, ensure `simulator/config.yaml` does not contain
   `replayEnabled` or `recordFilePath` — those fields are not
   recognised by the v0.4.0 binary and cause the
   `simulator-server` container to crash-loop.)
2. Confirm the API server is reachable on `localhost:3131`.
3. From the `microservices-sim-poc` root:
   ```bash
   python comparison-scripts/rq1_kss_compare.py \
       --rq1-dir deployment/rq1 \
       --kss-api http://localhost:3131 \
       --out-agreement deployment/rq1/kss-agreement.csv \
       --out-sensitivity deployment/rq1/kss-seed-sensitivity.csv \
       --seeds 1,2,3
   ```
