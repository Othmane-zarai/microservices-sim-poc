#!/usr/bin/env python3
"""Extract per-operation service-time distributions from a real Jaeger capture
(real-traces.json) for calibrating the M/G/c lognormal queue model.

For each (service, operation) it reports n, p50, p95, mean, std, the
coefficient of variation CV=std/mean, and a lognormal fit (median = exp(mean of
ln d); sigma = std of ln d). Durations are span durations in microseconds.

Usage: python calibrate_service_times.py real-traces.json [real-traces2.json ...]
A second file lets you compare load levels (e.g. moderate vs high) to see
whether per-operation service time is load-stable — the precondition for
calibrating at one load and predicting another rather than curve-fitting.
"""
from __future__ import annotations
import json, math, sys
from collections import defaultdict


def strip(op: str) -> str:
    # mirror compare_traces.py: drop grpc. / hipstershop.<Svc>/ prefixes
    op = op.split('.')[-1] if op.startswith('grpc.') else op
    return op.split('/')[-1]


def load(path):
    d = json.load(open(path))
    durs = defaultdict(list)
    for tr in d['data']:
        procs = tr.get('processes', {})
        for s in tr.get('spans', []):
            svc = procs.get(s.get('processID', ''), {}).get('serviceName', '?')
            op = strip(s.get('operationName', '?'))
            durs[(svc, op)].append(float(s['duration']))
    return durs


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(p * len(xs)))]


def stats(xs):
    n = len(xs)
    mean = sum(xs) / n
    var = sum((x - mean) ** 2 for x in xs) / n
    std = math.sqrt(var)
    logs = [math.log(x) for x in xs if x > 0]
    mlog = sum(logs) / len(logs)
    slog = math.sqrt(sum((l - mlog) ** 2 for l in logs) / len(logs))
    return dict(n=n, p50=pct(xs, .5), p95=pct(xs, .95), mean=mean, std=std,
                cv=std / mean if mean else 0, median=math.exp(mlog), sigma=slog)


def main():
    files = sys.argv[1:] or ['real-traces.json']
    per_file = [(f, load(f)) for f in files]
    keys = sorted(set().union(*[d.keys() for _, d in per_file]))
    for f, _ in per_file:
        print(f"# {f}")
    hdr = f"{'service/operation':38} {'n':>5} {'p50us':>8} {'p95us':>8} {'CV':>5} {'sigma':>5}"
    print(hdr)
    for k in keys:
        for f, d in per_file:
            if k not in d:
                continue
            s = stats(d[k])
            print(f"{(k[0]+'/'+k[1])[:38]:38} {s['n']:5d} {s['p50']:8.0f} "
                  f"{s['p95']:8.0f} {s['cv']:5.2f} {s['sigma']:5.2f}  [{f.split('/')[-1][:14]}]")


if __name__ == '__main__':
    main()
