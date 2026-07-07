# Online Boutique - Real vs Simulator Trace Comparison

- Real traces: **2000**, distinct (service, op) keys: 24
- Sim  traces: **6010**, distinct (service, op) keys: 22
- Operations seen on both sides: **8**

## 1. Per-operation latency (microseconds)

| service | operation | n_real | n_sim | p50_real | p50_sim | p95_real | p95_sim | log10(p95 ratio) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| checkoutservice | PlaceOrder | 383 | 417 | 1007627 | 2335 | 2255322 | 11447 | -2.29 |
| currencyservice | Convert | 1511 | 2577 | 34 | 1845 | 573 | 8075 | +1.15 |
| currencyservice | GetSupportedCurrencies | 382 | 2593 | 27 | 1744 | 250 | 8198 | +1.52 |
| emailservice | SendOrderConfirmation | 382 | 417 | 311 | 1645 | 1725 | 7940 | +0.66 |
| paymentservice | Charge | 383 | 417 | 407 | 1716 | 2518 | 7024 | +0.45 |
| productcatalogservice | GetProduct | 3032 | 6890 | 28 | 1741 | 397 | 7396 | +1.27 |
| productcatalogservice | ListProducts | 382 | 2593 | 36 | 1795 | 185 | 7721 | +1.62 |
| recommendationservice | ListRecommendations | 382 | 4331 | 15731 | 2085 | 300040 | 7928 | -1.58 |

Target: |log10(p95 ratio)| < 0.5 (simulator is M/M/c-driven; order-of-magnitude agreement is the goal).

## 2. Call-graph fan-out per root operation

### Root: `Convert`

| child operation | real count | sim count |
|---|---:|---:|
| Convert | 1511 | 0 |

### Root: `HTTP GET /`

| child operation | real count | sim count |
|---|---:|---:|
| GetAds | 0 | 2171 |
| GetProduct | 0 | 2171 |
| GetSupportedCurrencies | 0 | 2171 |
| ListProducts | 0 | 2171 |
| ListRecommendations | 0 | 2171 |

### Root: `HTTP GET /cart`

| child operation | real count | sim count |
|---|---:|---:|
| Convert | 0 | 858 |
| GetCart | 0 | 858 |
| GetProduct | 0 | 858 |
| GetQuote | 0 | 858 |
| HGETALL | 0 | 858 |
| ListRecommendations | 0 | 858 |

### Root: `HTTP GET /product/{id}`

| child operation | real count | sim count |
|---|---:|---:|
| Convert | 0 | 1302 |
| GetAds | 0 | 1302 |
| GetProduct | 0 | 2604 |
| ListRecommendations | 0 | 1302 |

### Root: `HTTP POST /cart`

| child operation | real count | sim count |
|---|---:|---:|
| AddItem | 0 | 840 |
| GetProduct | 0 | 840 |
| HSET | 0 | 840 |

### Root: `HTTP POST /cart/checkout`

| child operation | real count | sim count |
|---|---:|---:|
| Charge | 0 | 417 |
| Convert | 0 | 417 |
| EmptyCart | 0 | 417 |
| GetCart | 0 | 417 |
| GetProduct | 0 | 417 |
| GetQuote | 0 | 417 |
| HGETALL | 0 | 417 |
| PlaceOrder | 0 | 417 |
| SendOrderConfirmation | 0 | 417 |
| ShipOrder | 0 | 417 |

### Root: `HTTP POST /setCurrency`

| child operation | real count | sim count |
|---|---:|---:|
| GetSupportedCurrencies | 0 | 422 |
| ListProducts | 0 | 422 |

### Root: `frontend`

| child operation | real count | sim count |
|---|---:|---:|
| Charge | 766 | 0 |
| EmptyCart | 382 | 0 |
| GetCart | 383 | 0 |
| GetProduct | 6064 | 0 |
| GetQuote | 383 | 0 |
| GetSupportedCurrencies | 764 | 0 |
| ListProducts | 764 | 0 |
| ListRecommendations | 764 | 0 |
| PlaceOrder | 766 | 0 |
| SendOrderConfirmation | 764 | 0 |
| ShipOrder | 382 | 0 |

## 3. Operation coverage

### Operations only on real side
- `checkoutservice` / `Charge`
- `checkoutservice` / `Convert`
- `checkoutservice` / `EmptyCart`
- `checkoutservice` / `GetCart`
- `checkoutservice` / `GetProduct`
- `checkoutservice` / `GetQuote`
- `checkoutservice` / `SendOrderConfirmation`
- `checkoutservice` / `ShipOrder`
- `checkoutservice` / `grpc.health.v1.Health/Check`
- `checkoutservice` / `opentelemetry.proto.collector.trace.v1.TraceService/Export`
- `frontend` / `GetProduct`
- `frontend` / `GetSupportedCurrencies`
- `frontend` / `ListRecommendations`
- `frontend` / `PlaceOrder`
- `frontend` / `frontend`
- `recommendationservice` / `ListProducts`

### Operations only on sim side
- `adservice` / `GetAds`
- `cartservice` / `AddItem`
- `cartservice` / `EmptyCart`
- `cartservice` / `GetCart`
- `frontend` / `HTTP GET /`
- `frontend` / `HTTP GET /cart`
- `frontend` / `HTTP GET /product/{id}`
- `frontend` / `HTTP POST /cart`
- `frontend` / `HTTP POST /cart/checkout`
- `frontend` / `HTTP POST /setCurrency`
- `redis-cart` / `HGETALL`
- `redis-cart` / `HSET`
- `shippingservice` / `GetQuote`
- `shippingservice` / `ShipOrder`

