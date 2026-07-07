#!/usr/bin/env python3
"""Compare per-endpoint latency percentiles between Locust and the simulator.

Inputs:
  real-latency.csv  — Locust stats CSV (Name, Median, 95%, 99%, …)
  sim-latency.csv   — serviceRequest, startTime, duration, status

Output: latency-comparison.csv with one row per (endpoint, percentile).

Usage:
    python rq4_latency.py deployment/rq4/real-latency.csv \\
                          deployment/rq4/sim-latency.csv \\
        > deployment/rq4/latency-comparison.csv

Name normalisation:
  Locust emits names like "GET /" or "POST /cart".
  The simulator emits the pod-name prefix, e.g. "web".
  All HTTP-method-prefixed names are mapped to "web" so the two sides match.
"""
from __future__ import annotations

import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

PERCENTILES = (50, 95, 99)
_HTTP_METHODS = ("GET ", "POST ", "PUT ", "DELETE ", "PATCH ", "HEAD ", "OPTIONS ")


def _to_deployment_name(locust_name: str) -> str:
    """Map 'GET /' → 'web'; non-HTTP names pass through unchanged."""
    for method in _HTTP_METHODS:
        if locust_name.startswith(method):
            return "web"
    return locust_name


def load_real(p: Path) -> dict[str, dict[int, float]]:
    """Locust stats: rows normalised by deployment name, percentiles averaged.

    Multiple Locust endpoints (GET /, POST /cart …) that map to the same
    deployment (web) are averaged together so the comparison is deployment-level.
    """
    raw: dict[str, list[dict[int, float]]] = defaultdict(list)
    with p.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            name = row.get("Name", "").strip()
            if not name or name == "Aggregated":
                continue
            key = _to_deployment_name(name)
            try:
                raw[key].append({
                    50: float(row.get("50%", row.get("Median", 0)) or 0),
                    95: float(row.get("95%", 0) or 0),
                    99: float(row.get("99%", 0) or 0),
                })
            except ValueError:
                continue
    return {
        key: {pct: sum(e[pct] for e in entries) / len(entries) for pct in PERCENTILES}
        for key, entries in raw.items()
    }


def load_sim(p: Path) -> dict[str, list[float]]:
    """sim-latency.csv: per-endpoint list of durations (ms — assumed)."""
    out: dict[str, list[float]] = defaultdict(list)
    with p.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            name = (row.get("serviceRequest") or "").strip()
            try:
                duration = float(row.get("duration", 0) or 0)
            except ValueError:
                continue
            if name and duration > 0:
                out[name].append(duration)
    return out


def percentile(data: list[float], p: int) -> float:
    if not data:
        return float("nan")
    data = sorted(data)
    k = (len(data) - 1) * (p / 100)
    f = int(k)
    c = min(f + 1, len(data) - 1)
    return data[f] + (data[c] - data[f]) * (k - f)


def main() -> None:
    if len(sys.argv) < 3:
        sys.exit("usage: rq4_latency.py <real.csv> <sim.csv>")
    real = load_real(Path(sys.argv[1]))
    sim = load_sim(Path(sys.argv[2]))

    # Ensure UTF-8 output with Unix line endings regardless of platform locale.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    w = csv.writer(sys.stdout, lineterminator="\n")
    w.writerow(["endpoint", "percentile", "real_ms", "sim_ms", "ratio"])

    fail_count = 0
    for endpoint in sorted(set(real) | set(sim)):
        real_pct = real.get(endpoint, {})
        sim_data = sim.get(endpoint, [])
        for p in PERCENTILES:
            r = real_pct.get(p, float("nan"))
            s = percentile(sim_data, p)
            ratio = (s / r) if (r and r == r) else float("nan")
            w.writerow([endpoint, p, f"{r:.2f}", f"{s:.2f}", f"{ratio:.3f}"])
            threshold = 2.0 if p == 50 else 1.5
            if ratio == ratio and ratio > threshold:
                fail_count += 1
                print(
                    f"FAIL  {endpoint:<20s}  p{p}: real={r:.1f}ms sim={s:.1f}ms ratio={ratio:.2f} (>{threshold})",
                    file=sys.stderr,
                )

    if fail_count == 0:
        print("\nRQ4: all per-endpoint percentile ratios within thresholds.", file=sys.stderr)
    else:
        print(f"\nRQ4: {fail_count} percentile rows exceed threshold.", file=sys.stderr)


if __name__ == "__main__":
    main()
