#!/usr/bin/env python3
"""Parse the most recent loadgenerator (Locust) stats block from kubectl logs.

Locust headless prints stats periodically to stdout. The format we target
(Locust 2.x) is one line per (method, path) entry:

    GET      /                  195     0(0.00%) |  113   41   454   100 | 0.10   0.00

Numeric columns after the bars: avg, min, max, med, then req/s and failures/s.
We emit p50 = med, with sentinel p95/p99 = max because headless mode without
`--csv` does not expose percentiles. The real RQ uses these only for
order-of-magnitude latency comparison.
"""
from __future__ import annotations

import re
import sys

ROW_RE = re.compile(
    r'^(?P<method>GET|POST|PUT|DELETE)\s+(?P<path>\S+)\s+'
    r'(?P<reqs>\d+)\s+\d+\([\d.]+%\)\s*\|\s*'
    r'(?P<avg>\d+)\s+(?P<mn>\d+)\s+(?P<mx>\d+)\s+(?P<med>\d+)\s*\|\s*'
    r'(?P<rps>[\d.]+)\s+[\d.]+\s*$'
)


def main() -> None:
    text = sys.stdin.read()
    rows = []
    for line in text.splitlines():
        m = ROW_RE.match(line)
        if not m:
            continue
        rows.append({
            'method': m['method'],
            'path': m['path'],
            'p50_ms': m['med'],
            'p95_ms': m['mx'],     # no true p95 in stdout; max stands in
            'p99_ms': m['mx'],
            'rps': m['rps'],
        })
    # Dedupe — Locust prints the same row at each stats interval, so keep
    # the last occurrence (most-recent / largest sample size).
    by_key = {}
    for r in rows:
        by_key[(r['method'], r['path'])] = r
    print('method,path,p50_ms,p95_ms,p99_ms,rps')
    for k in sorted(by_key):
        r = by_key[k]
        print(f"{r['method']},{r['path']},{r['p50_ms']},{r['p95_ms']},{r['p99_ms']},{r['rps']}")


if __name__ == '__main__':
    main()
