# Online Boutique — Real vs Simulator Comparison

## Placement agreement

- Winner agreement: **1/30 = 3.3%**
- Target: >= 90%

## HPA trajectory NRMSD

| Service | NRMSD |
|---|---|
| frontend | 0.480 ✗ |
| checkoutservice | 0.000 ✓ |
| recommendationservice | 0.738 ✗ |

Target: NRMSD < 0.20 per service.

## Latency sim/real ratios (sim M/M/c vs Locust real)

| sim_endpoint               | real_endpoint           |   p50_real |   p50_sim |   p95_real |   p95_sim |   p95_ratio |
|:---------------------------|:------------------------|-----------:|----------:|-----------:|----------:|------------:|
| GET /frontend              | GET /                   |   3200.000 |     2.751 |   8480.000 |    10.156 |       0.001 |
| GET /checkoutservice       | POST /cart/checkout     |   1500.000 |     2.463 |   6078.000 |    11.448 |       0.002 |
| GET /recommendationservice | GET /                   |   3200.000 |     2.114 |   8480.000 |     7.652 |       0.001 |
| GET /productcatalogservice | GET /product/0PUK6V6EV0 |    940.000 |     1.702 |   2989.000 |     7.540 |       0.003 |
| GET /cartservice           | GET /cart               |   1500.000 |     1.761 |   6079.000 |     7.918 |       0.001 |

Target: p95 ratio < 0.5 (sim is a lower bound by design).
