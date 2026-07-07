# Online Boutique - Real vs Simulator Trace Comparison

- Real traces: **1020**, distinct (service, op) keys: 36
- Sim  traces: **6010**, distinct (service, op) keys: 22
- Operations seen on both sides: **8**

## 1. Per-operation latency (microseconds)

| service | operation | n_real | n_sim | p50_real | p50_sim | p95_real | p95_sim | log10(p95 ratio) |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| checkoutservice | PlaceOrder | 107 | 417 | 25239 | 1422 | 63608 | 5965 | -1.03 |
| currencyservice | Convert | 1429 | 2577 | 111 | 1845 | 856 | 8075 | +0.97 |
| currencyservice | GetSupportedCurrencies | 710 | 2593 | 114 | 1744 | 1097 | 8198 | +0.87 |
| emailservice | SendOrderConfirmation | 113 | 417 | 269 | 1645 | 943 | 7940 | +0.93 |
| paymentservice | Charge | 103 | 417 | 367 | 1716 | 1729 | 7024 | +0.61 |
| productcatalogservice | GetProduct | 4270 | 6890 | 25 | 1741 | 107 | 7396 | +1.84 |
| productcatalogservice | ListProducts | 729 | 2593 | 34 | 1795 | 163 | 7721 | +1.68 |
| recommendationservice | ListRecommendations | 332 | 4331 | 5521 | 4971 | 46383 | 17875 | -0.41 |

Target: |log10(p95 ratio)| < 0.5 (simulator is M/M/c-driven; order-of-magnitude agreement is the goal).

## 2. Call-graph fan-out per root operation

### Root: `Charge`

| child operation | real count | sim count |
|---|---:|---:|
| Charge | 1 | 0 |
| EmptyCart | 1 | 0 |
| GetCart | 1 | 0 |
| GetProduct | 2 | 0 |
| GetQuote | 1 | 0 |
| PlaceOrder | 2 | 0 |
| SendOrderConfirmation | 2 | 0 |
| ShipOrder | 1 | 0 |

### Root: `Convert`

| child operation | real count | sim count |
|---|---:|---:|
| Convert | 144 | 0 |

### Root: `GetSupportedCurrencies`

| child operation | real count | sim count |
|---|---:|---:|
| Charge | 2 | 0 |
| Convert | 26 | 0 |
| EmptyCart | 2 | 0 |
| GetCart | 7 | 0 |
| GetProduct | 48 | 0 |
| GetQuote | 6 | 0 |
| GetSupportedCurrencies | 5 | 0 |
| ListProducts | 9 | 0 |
| ListRecommendations | 7 | 0 |
| PlaceOrder | 2 | 0 |
| SendOrderConfirmation | 4 | 0 |
| ShipOrder | 2 | 0 |
| frontend | 3 | 0 |

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
| Charge | 203 | 0 |
| Convert | 2688 | 0 |
| EmptyCart | 101 | 0 |
| GetAds | 444 | 0 |
| GetCart | 720 | 0 |
| GetProduct | 8619 | 0 |
| GetQuote | 276 | 0 |
| GetSupportedCurrencies | 1436 | 0 |
| ListProducts | 1111 | 0 |
| ListRecommendations | 1004 | 0 |
| PlaceOrder | 214 | 0 |
| SendOrderConfirmation | 205 | 0 |
| ShipOrder | 101 | 0 |

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
- `currencyservice` / `grpc.grpc.health.v1.Health/Check`
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

