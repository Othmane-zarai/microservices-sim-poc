# Online Boutique - Real vs Simulator Trace Comparison

- Real traces: **1215**, distinct (service, op) keys: 35
- Sim  traces: **6010**, distinct (service, op) keys: 22
- Operations seen on both sides: **8**

## 1. Per-operation latency (microseconds)

| service | operation | n_real | n_sim | p50_real | p50_sim | p95_real | p95_sim | log10(p95 ratio) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| checkoutservice | PlaceOrder | 212 | 417 | 237653 | 2335 | 1988618 | 11447 | -2.24 |
| currencyservice | Convert | 1160 | 2577 | 35 | 1845 | 280 | 8075 | +1.46 |
| currencyservice | GetSupportedCurrencies | 603 | 2593 | 30 | 1744 | 516 | 8198 | +1.20 |
| emailservice | SendOrderConfirmation | 202 | 417 | 575 | 1645 | 2283 | 7940 | +0.54 |
| paymentservice | Charge | 212 | 417 | 999 | 1716 | 4333 | 7024 | +0.21 |
| productcatalogservice | GetProduct | 3092 | 6890 | 27 | 1741 | 236 | 7396 | +1.50 |
| productcatalogservice | ListProducts | 522 | 2593 | 34 | 1795 | 298 | 7721 | +1.41 |
| recommendationservice | ListRecommendations | 344 | 4331 | 93827 | 2437 | 212134 | 9334 | -1.36 |

Target: |log10(p95 ratio)| < 0.5 (simulator is M/M/c-driven; order-of-magnitude agreement is the goal).

## 2. Call-graph fan-out per root operation

### Root: `Charge`

| child operation | real count | sim count |
|---|---:|---:|
| Charge | 24 | 0 |
| EmptyCart | 39 | 0 |
| GetCart | 23 | 0 |
| GetProduct | 99 | 0 |
| GetQuote | 24 | 0 |
| GetSupportedCurrencies | 3 | 0 |
| ListProducts | 44 | 0 |
| ListRecommendations | 38 | 0 |
| PlaceOrder | 42 | 0 |
| SendOrderConfirmation | 65 | 0 |
| ShipOrder | 27 | 0 |

### Root: `Convert`

| child operation | real count | sim count |
|---|---:|---:|
| Convert | 194 | 0 |
| ListProducts | 5 | 0 |
| ListRecommendations | 5 | 0 |

### Root: `GetSupportedCurrencies`

| child operation | real count | sim count |
|---|---:|---:|
| Charge | 9 | 0 |
| Convert | 356 | 0 |
| EmptyCart | 9 | 0 |
| GetCart | 116 | 0 |
| GetProduct | 403 | 0 |
| GetQuote | 13 | 0 |
| GetSupportedCurrencies | 111 | 0 |
| ListProducts | 85 | 0 |
| ListRecommendations | 18 | 0 |
| PlaceOrder | 14 | 0 |
| SendOrderConfirmation | 20 | 0 |
| ShipOrder | 9 | 0 |

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
| AddItem | 41 | 0 |
| Charge | 309 | 0 |
| Convert | 1551 | 0 |
| EmptyCart | 156 | 0 |
| GetAds | 214 | 0 |
| GetCart | 439 | 0 |
| GetProduct | 5614 | 0 |
| GetQuote | 247 | 0 |
| GetSupportedCurrencies | 922 | 0 |
| ListProducts | 624 | 0 |
| ListRecommendations | 576 | 0 |
| PlaceOrder | 322 | 0 |
| SendOrderConfirmation | 317 | 0 |
| ShipOrder | 157 | 0 |

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
- `emailservice` / `/grpc.health.v1.Health/Check`
- `frontend` / `AddItem`
- `frontend` / `Convert`
- `frontend` / `GetAds`
- `frontend` / `GetCart`
- `frontend` / `GetProduct`
- `frontend` / `GetQuote`
- `frontend` / `GetSupportedCurrencies`
- `frontend` / `ListProducts`
- `frontend` / `ListRecommendations`
- `frontend` / `PlaceOrder`
- `frontend` / `frontend`
- `frontend` / `opentelemetry.proto.collector.trace.v1.TraceService/Export`
- `paymentservice` / `grpc.grpc.health.v1.Health/Check`
- `productcatalogservice` / `opentelemetry.proto.collector.trace.v1.TraceService/Export`
- `recommendationservice` / `/grpc.health.v1.Health/Check`
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

