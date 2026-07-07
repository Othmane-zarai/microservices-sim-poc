#!/usr/bin/env bash
# Pull traces from Jaeger for every service except jaeger-all-in-one and
# concatenate them into real-traces.json (Jaeger v1 JSON schema, matching
# sim-traces.json so compare_traces.py treats them symmetrically).
#
# Requires kubectl context pointing at the cluster Jaeger is on.
set -euo pipefail

NS=${NS:-observability}
LIMIT=${LIMIT:-500}
LOOKBACK=${LOOKBACK:-30m}

echo "==> port-forwarding Jaeger UI"
kubectl port-forward -n "$NS" svc/jaeger 16686:16686 >/dev/null 2>&1 &
PF_PID=$!
trap "kill $PF_PID 2>/dev/null || true" EXIT
sleep 4

echo "==> listing services"
SERVICES=$(curl -s --max-time 10 http://localhost:16686/api/services \
    | python -c "import json,sys; d=json.load(sys.stdin)['data']; print('\n'.join(s for s in d if not s.startswith('jaeger') and not s.startswith('unknown_service')))")
echo "    services: $(echo $SERVICES | tr '\n' ' ')"

echo "==> pulling traces per service (limit=$LIMIT lookback=$LOOKBACK)"
python << EOF
import json, urllib.request, urllib.parse, sys
services = """$SERVICES""".strip().split()
all_data = []
seen = set()
for svc in services:
    qs = urllib.parse.urlencode({'service': svc, 'limit': str($LIMIT), 'lookback': '$LOOKBACK'})
    url = f'http://localhost:16686/api/traces?{qs}'
    try:
        with urllib.request.urlopen(url, timeout=15) as f:
            payload = json.load(f)
    except Exception as e:
        print(f'    {svc}: ERROR {e}', file=sys.stderr)
        continue
    n_new = 0
    for t in payload.get('data', []):
        if t['traceID'] in seen:
            continue
        seen.add(t['traceID'])
        all_data.append(t)
        n_new += 1
    print(f'    {svc}: +{n_new} traces (cumulative {len(all_data)})', file=sys.stderr)
with open('real-traces.json', 'w') as f:
    json.dump({'data': all_data}, f)
print(f'wrote real-traces.json ({len(all_data)} unique traces)', file=sys.stderr)
EOF

echo "==> done"
