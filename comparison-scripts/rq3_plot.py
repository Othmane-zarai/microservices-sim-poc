#!/usr/bin/env python3
"""Plot RQ3 scalability sweep on log-log axes.

Input: scalability.csv with rows `nodes, pods, seconds, peakHeapMiB`.

Usage:
    python rq3_plot.py deployment/rq3/scalability.csv \\
        --out deployment/rq3/scalability.pdf
"""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", type=Path)
    ap.add_argument("--out", type=Path, default=Path("scalability.pdf"))
    args = ap.parse_args()

    rows = []
    with args.csv.open(encoding="utf-8") as f:
        for line in f:
            parts = [c.strip() for c in line.split(",")]
            if len(parts) >= 4 and parts[0].isdigit():
                rows.append(
                    {
                        "nodes": int(parts[0]),
                        "pods": int(parts[1]),
                        "seconds": float(parts[2]),
                        "heap": float(parts[3]),
                    }
                )

    if not rows:
        sys.exit("no parseable rows in input")

    # Compute events/sec proxy (pods per second).
    print("nodes,pods,seconds,heapMiB,podsPerSec", flush=True)
    for r in rows:
        eps = r["pods"] / r["seconds"] if r["seconds"] else float("nan")
        print(f"{r['nodes']},{r['pods']},{r['seconds']:.2f},{r['heap']:.0f},{eps:.0f}")

    try:
        import matplotlib.pyplot as plt
    except ImportError:
        print("matplotlib not installed; skipping plot", file=sys.stderr)
        return

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(10, 4))
    for nodes in sorted({r["nodes"] for r in rows}):
        sub = [r for r in rows if r["nodes"] == nodes]
        sub.sort(key=lambda r: r["pods"])
        ax1.loglog(
            [r["pods"] for r in sub],
            [r["seconds"] for r in sub],
            "o-",
            label=f"{nodes} nodes",
        )
        ax2.loglog(
            [r["pods"] for r in sub],
            [r["heap"] for r in sub],
            "o-",
            label=f"{nodes} nodes",
        )
    ax1.set_xlabel("pods")
    ax1.set_ylabel("seconds")
    ax1.set_title("Wall-clock vs pod count")
    ax1.legend()
    ax1.grid(True, which="both", alpha=0.3)
    ax2.set_xlabel("pods")
    ax2.set_ylabel("peak heap (MiB)")
    ax2.set_title("Memory vs pod count")
    ax2.legend()
    ax2.grid(True, which="both", alpha=0.3)
    fig.tight_layout()
    fig.savefig(args.out, dpi=150)
    print(f"\nSaved {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
