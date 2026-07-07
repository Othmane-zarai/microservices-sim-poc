#!/usr/bin/env python3
"""Extract sim-side latency CSV from K8sClusterFromYamlExample log output.

Reads the LATENCY_CSV_BEGIN ... LATENCY_CSV_END block emitted when
-Dk8s.emitLatencyCsv=true (or env K8S_EMIT_LATENCY_CSV=true).

Usage:
    python rq4_extract.py deployment/rq4/sim-with-latency.log \\
        > deployment/rq4/sim-latency.csv
"""
from __future__ import annotations

import sys
from pathlib import Path


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit("usage: rq4_extract.py <sim-log-path>")
    log = Path(sys.argv[1])
    inside = False
    saw_header = False
    for line in log.read_text(encoding="utf-8").splitlines():
        if line.strip() == "LATENCY_CSV_END":
            break
        if inside:
            if not saw_header:
                # First line inside is the header row.
                print(line)
                saw_header = True
            elif line.strip():
                print(line)
        if line.strip() == "LATENCY_CSV_BEGIN":
            inside = True


if __name__ == "__main__":
    main()
