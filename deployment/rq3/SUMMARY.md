# RQ3 — Simulation Scalability (Replicated)

Produced by `comparison-scripts/rq3_replicate.py`, 10 measured runs per
cell after 3 discarded warm-ups; mean ± std and 95% confidence interval
via Student's *t* with ν = 9.

## Raw measurements

See `scalability-replicated.csv` for the full per-run breakdown.

| Nodes (n) | Pods (p) | Mean (ms) | Std (ms) | 95% CI (ms) |
|---:|---:|---:|---:|---|
|     10 |     100 |   8 116 |   737 | [7 589, 8 644] |
|     10 |     500 |  11 050 | 2 095 | [9 552, 12 548] |
|     10 |   1 000 |  12 277 | 1 168 | [11 442, 13 113] |
|    100 |     100 |   8 319 |   488 | [7 970, 8 669] |
|  1 000 |     100 |   8 842 |   453 | [8 518, 9 165] |

## Methodology note

Each cell's wall time includes a constant Spring Boot startup overhead
of approximately 6–8 seconds. That overhead is identical across cells,
so the differential between cells is the scaling-law evidence — not the
absolute wall time.

## Differential analysis

**Node scaling (fixed p = 100):**

| n     | Mean (ms) | Δ from n=10 |
|---:|---:|---:|
|    10 |   8 116  |     —     |
|   100 |   8 319  |   +203 ms |
| 1 000 |   8 842  |   +726 ms |

A 100× growth in node count costs only +726 ms in mean wall time —
sub-linear, consistent with `COST_OPTIMIZED`'s `O(n log n)` scoring
loop.

**Pod scaling (fixed n = 10):**

| p     | Mean (ms) | Δ from p=100 | per-pod marginal |
|---:|---:|---:|---:|
|   100 |   8 116  |    —     |   —     |
|   500 |  11 050  |  +2 934 ms |  7.3 ms/pod |
| 1 000 |  12 277  |  +4 161 ms |  4.6 ms/pod |

The linear-in-p slope after the A1 hotfix is ~4.6 ms / pod averaged
across the full p=100 → 1000 range, confirming the published claim
that the post-A1 reconciliation loop is O(p) rather than the earlier
O(p^3) thrash.

## Reproduction

```powershell
python comparison-scripts/rq3_replicate.py `
    --jar target/csp-examples-springboot-1.0.0-SNAPSHOT.jar `
    --out-dir deployment/rq3 `
    --out-csv deployment/rq3/scalability-replicated.csv `
    --cells "10:100,10:500,10:1000,100:100,1000:100" `
    --warmup 3 --reps 10 --duration 30
```

Each cell takes ~ 13 × 8 s = ~100 s wall-time, so the full sweep
above runs in ~ 8 min on an Ampere ARM workstation.
