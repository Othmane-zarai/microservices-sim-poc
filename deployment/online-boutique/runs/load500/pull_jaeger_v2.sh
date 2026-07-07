#!/usr/bin/env bash
set -euo pipefail
kubectl port-forward -n observability svc/jaeger 16686:16686 >/dev/null 2>&1 &
PF_PID=$!
trap "kill $PF_PID 2>/dev/null || true" EXIT
sleep 4
SERVICES="frontend checkoutservice currencyservice productcatalogservice recommendationservice paymentservice emailservice cartservice adservice shippingservice"
python << 'PY'
import json, urllib.request, urllib.parse, sys, time
services = "frontend checkoutservice currencyservice productcatalogservice recommendationservice paymentservice emailservice cartservice adservice shippingservice".split()
all_data, seen = [], set()
for svc in services:
    qs = urllib.parse.urlencode({'service': svc, 'limit': '200', 'lookback': '15m'})
    url = f'http://localhost:16686/api/traces?{qs}'
    try:
        with urllib.request.urlopen(url, timeout=60) as f:
            payload = json.load(f)
    except Exception as e:
        print(f'    {svc}: ERROR {e}', file=sys.stderr); continue
    n = 0
    for t in payload.get('data', []):
        if t['traceID'] in seen: continue
        seen.add(t['traceID']); all_data.append(t); n += 1
    print(f'    {svc}: +{n} traces (cum {len(all_data)})', file=sys.stderr)
    time.sleep(2)
with open('real-traces.json', 'w') as f:
    json.dump({'data': all_data}, f)
print(f'wrote real-traces.json ({len(all_data)} unique traces)', file=sys.stderr)
PY
