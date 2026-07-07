# Online Boutique — Real vs Simulator Comparison

## Placement agreement

- Winner agreement: **5/18 = 27.8%**
- Target: >= 90%

## HPA trajectory NRMSD

| Service | NRMSD |
|---|---|
| frontend | 0.000 ✓ |
| checkoutservice | 0.000 ✓ |
| recommendationservice | 0.000 ✓ |

Target: NRMSD < 0.20 per service.

## Latency sim/real ratios (sim M/M/c vs Locust real)

| sim_endpoint               | real_endpoint           |   p50_real |   p50_sim |   p95_real |   p95_sim |   p95_ratio |
|:---------------------------|:------------------------|-----------:|----------:|-----------:|----------:|------------:|
| GET /frontend              | GET /                   |     77.000 |     2.816 |   2296.000 |    11.217 |       0.005 |
| GET /checkoutservice       | POST /cart/checkout     |    130.000 |     1.526 |   2758.000 |     6.575 |       0.002 |
| GET /recommendationservice | GET /                   |     77.000 |     1.831 |   2296.000 |     7.099 |       0.003 |
| GET /productcatalogservice | GET /product/0PUK6V6EV0 |    160.000 |     1.702 |   2136.000 |     7.540 |       0.004 |
| GET /cartservice           | GET /cart               |    190.000 |     1.761 |   2060.000 |     7.918 |       0.004 |

Target: p95 ratio < 0.5 (sim is a lower bound by design).
