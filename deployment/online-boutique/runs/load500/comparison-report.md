# Online Boutique — Real vs Simulator Comparison

## Placement agreement

- Winner agreement: **1/30 = 3.3%**
- Target: >= 90%

## HPA trajectory NRMSD

| Service | NRMSD |
|---|---|
| frontend | 0.444 ✗ |
| checkoutservice | 0.000 ✓ |
| recommendationservice | 0.767 ✗ |

Target: NRMSD < 0.20 per service.

## Latency sim/real ratios (sim M/M/c vs Locust real)

| sim_endpoint               | real_endpoint           |   p50_real |   p50_sim |   p95_real |   p95_sim |   p95_ratio |
|:---------------------------|:------------------------|-----------:|----------:|-----------:|----------:|------------:|
| GET /frontend              | GET /                   |    180.000 |     2.751 |   1966.000 |    10.156 |       0.005 |
| GET /checkoutservice       | POST /cart/checkout     |    630.000 |     2.463 |   2228.000 |    11.448 |       0.005 |
| GET /recommendationservice | GET /                   |    180.000 |     2.437 |   1966.000 |     8.897 |       0.005 |
| GET /productcatalogservice | GET /product/0PUK6V6EV0 |    600.000 |     1.702 |   2222.000 |     7.540 |       0.003 |
| GET /cartservice           | GET /cart               |    610.000 |     1.761 |   2263.000 |     7.918 |       0.003 |

Target: p95 ratio < 0.5 (sim is a lower bound by design).
