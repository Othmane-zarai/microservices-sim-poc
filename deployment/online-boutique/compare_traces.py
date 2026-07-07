#!/usr/bin/env python3
"""Compare real Jaeger traces against the simulator's sim-traces.json.

Real-side input:
    - Either a `real-traces.json` file pre-fetched from Jaeger's HTTP API:
          curl 'http://JAEGER/api/traces?service=frontend&limit=2000&lookback=1h' > real-traces.json
    - Or pull live via `--jaeger-url http://localhost:16686 --service frontend`.

Sim-side input:
    - `sim-traces.json` produced by K8sOnlineBoutiqueExample with
      `-Dk8s.emitTracesJson=true`.

Outputs `trace-comparison.md` with three blocks:

1. Per-operation latency comparison (real vs sim p50 / p95)
2. Call-graph structural agreement (fan-out per root operation)
3. Operation-coverage check (operations seen on both sides)

This is the analysis component for Phase C of the empirical validation.
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path


def load_jaeger_payload(path: Path) -> list:
    """Reads Jaeger v1 JSON (the format also produced by JaegerJsonExporter)."""
    with path.open() as f:
        j = json.load(f)
    return j.get('data', [])


def fetch_real_traces(jaeger_url: str, service: str, limit: int, lookback: str) -> list:
    qs = urllib.parse.urlencode({'service': service, 'limit': str(limit), 'lookback': lookback})
    url = f"{jaeger_url.rstrip('/')}/api/traces?{qs}"
    with urllib.request.urlopen(url, timeout=10) as f:
        j = json.load(f)
    return j.get('data', [])


def _normalize_op(op: str) -> str:
    """Strip gRPC/OTel naming variations so real and sim names align.

    Strips a `hipstershop.<Service>/` or `grpc.hipstershop.<Service>/` prefix
    when present; leaves HTTP-route ops like `HTTP GET /` untouched.
    """
    if not op:
        return op
    if 'hipstershop.' in op:
        base = op.split('/')[-1]
        return base or op
    return op


def collect_op_latencies(traces: list) -> dict:
    """Map (service, operation) -> [duration_us, ...] from a list of Jaeger traces."""
    out = defaultdict(list)
    for t in traces:
        # Jaeger traces have processes table per trace; resolve service via processID.
        processes = t.get('processes', {})
        for s in t.get('spans', []):
            pid = s.get('processID')
            svc = processes.get(pid, {}).get('serviceName', 'unknown')
            op = _normalize_op(s.get('operationName', 'unknown'))
            dur = s.get('duration', 0)
            out[(svc, op)].append(dur)
    return out


def collect_fanout(traces: list) -> dict:
    """Map root-operation -> {child operation -> count}."""
    out = defaultdict(lambda: defaultdict(int))
    for t in traces:
        spans = t.get('spans', [])
        if not spans:
            continue
        roots = [s for s in spans if not s.get('references')]
        if not roots:
            continue
        root = min(roots, key=lambda x: x.get('startTime', 0))
        root_op = _normalize_op(root.get('operationName', 'unknown'))
        for s in spans:
            if s is root:
                continue
            out[root_op][_normalize_op(s.get('operationName', 'unknown'))] += 1
    return out


def percentile(samples: list, p: float) -> float:
    if not samples:
        return float('nan')
    s = sorted(samples)
    idx = min(len(s) - 1, max(0, int(round((p / 100.0) * (len(s) - 1)))))
    return float(s[idx])


def log_ratio(a: float, b: float) -> float:
    if a <= 0 or b <= 0 or math.isnan(a) or math.isnan(b):
        return float('nan')
    return math.log10(a / b)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--real', type=Path, default=Path('real-traces.json'))
    ap.add_argument('--sim',  type=Path, default=Path('sim-traces.json'))
    ap.add_argument('--out',  type=Path, default=Path('trace-comparison.md'))
    ap.add_argument('--jaeger-url', help='Pull real traces live from this Jaeger UI host')
    ap.add_argument('--service', default='frontend', help='Service to query on the real side')
    ap.add_argument('--limit', type=int, default=2000)
    ap.add_argument('--lookback', default='1h')
    args = ap.parse_args()

    if args.jaeger_url:
        print(f'[real] fetching from {args.jaeger_url} service={args.service}', file=sys.stderr)
        real = fetch_real_traces(args.jaeger_url, args.service, args.limit, args.lookback)
    elif args.real.exists():
        print(f'[real] loading {args.real}', file=sys.stderr)
        real = load_jaeger_payload(args.real)
    else:
        print(f'ERROR: real traces unavailable. Pass --jaeger-url, or save Jaeger API output to {args.real}.',
              file=sys.stderr)
        return 1

    print(f'[sim]  loading {args.sim}', file=sys.stderr)
    sim = load_jaeger_payload(args.sim)

    print(f'[real] {len(real)} traces, [sim] {len(sim)} traces', file=sys.stderr)

    real_lat = collect_op_latencies(real)
    sim_lat  = collect_op_latencies(sim)
    real_fo  = collect_fanout(real)
    sim_fo   = collect_fanout(sim)

    common = sorted(set(real_lat.keys()) & set(sim_lat.keys()))
    real_only = sorted(set(real_lat.keys()) - set(sim_lat.keys()))
    sim_only  = sorted(set(sim_lat.keys()) - set(real_lat.keys()))

    with args.out.open('w', encoding='utf-8') as out:
        out.write('# Online Boutique - Real vs Simulator Trace Comparison\n\n')
        out.write(f'- Real traces: **{len(real)}**, distinct (service, op) keys: {len(real_lat)}\n')
        out.write(f'- Sim  traces: **{len(sim)}**, distinct (service, op) keys: {len(sim_lat)}\n')
        out.write(f'- Operations seen on both sides: **{len(common)}**\n\n')

        out.write('## 1. Per-operation latency (microseconds)\n\n')
        out.write('| service | operation | n_real | n_sim | p50_real | p50_sim | p95_real | p95_sim | log10(p95 ratio) |\n')
        out.write('|---|---|---:|---:|---:|---:|---:|---:|---:|\n')
        for key in common:
            svc, op = key
            r = real_lat[key]; s = sim_lat[key]
            r50, r95 = percentile(r, 50), percentile(r, 95)
            s50, s95 = percentile(s, 50), percentile(s, 95)
            lr = log_ratio(s95, r95)
            out.write(f'| {svc} | {op} | {len(r)} | {len(s)} | {r50:.0f} | {s50:.0f} | {r95:.0f} | {s95:.0f} | {lr:+.2f} |\n')

        out.write('\nTarget: |log10(p95 ratio)| < 0.5 (simulator is M/M/c-driven; '
                  'order-of-magnitude agreement is the goal).\n\n')

        out.write('## 2. Call-graph fan-out per root operation\n\n')
        all_roots = sorted(set(real_fo.keys()) | set(sim_fo.keys()))
        for root in all_roots:
            out.write(f'### Root: `{root}`\n\n')
            out.write('| child operation | real count | sim count |\n|---|---:|---:|\n')
            real_children = real_fo.get(root, {})
            sim_children  = sim_fo.get(root, {})
            all_children = sorted(set(real_children) | set(sim_children))
            for ch in all_children:
                out.write(f'| {ch} | {real_children.get(ch, 0)} | {sim_children.get(ch, 0)} |\n')
            out.write('\n')

        out.write('## 3. Operation coverage\n\n')
        if real_only:
            out.write('### Operations only on real side\n')
            for k in real_only:
                out.write(f'- `{k[0]}` / `{k[1]}`\n')
            out.write('\n')
        if sim_only:
            out.write('### Operations only on sim side\n')
            for k in sim_only:
                out.write(f'- `{k[0]}` / `{k[1]}`\n')
            out.write('\n')
        if not real_only and not sim_only:
            out.write('Full overlap between sim and real operations.\n\n')

    print(f'Wrote {args.out}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
