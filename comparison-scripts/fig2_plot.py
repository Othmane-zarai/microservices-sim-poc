#!/usr/bin/env python3
"""Figure 2 diagnostic plot for the K8s CloudSimPlus paper.

Top panel   : CPU utilisation — avg demand, p95 demand, peak node utilisation.
              avgAllocated is dropped (identical to avgDemand — perfect allocation).
              maxDemand is dropped (clamped flat at 100% with cpuMultiplier=4.5).
              maxNodeUtil replaces both: shows per-node saturation pressure (86-100%),
              which is the quantity a scheduler paper actually needs to exhibit.
Bottom panel: Ready-pod count (left axis) and active-node count (right axis).
              Y-axes are set to [0, 120] / [0, 40] so the steady lines are
              shown in context rather than auto-scaled to a ±4-pod window.

Input CSV columns (produced by K8sPlanetLabExample stdout):
    t, avgDemand, p95Demand, maxDemand, avgAllocated, maxNodeUtil, readyPods, activeNodes

Usage:
    python comparison-scripts/fig2_plot.py planetlab_timeline.csv \\
        --out paper/figures/plots.pdf
"""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", type=Path, help="Timeline CSV from K8sPlanetLabExample")
    ap.add_argument("--out", type=Path, default=Path("paper/figures/plots.pdf"))
    args = ap.parse_args()

    rows: list[dict] = []
    with args.csv.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append(
                {
                    "t": float(row["t"]) / 60,  # seconds → minutes
                    "avgDemand": float(row["avgDemand"]) * 100,
                    "p95Demand": float(row["p95Demand"]) * 100,
                    "maxNodeUtil": float(row["maxNodeUtil"]) * 100,
                    "readyPods": int(row["readyPods"]),
                    "activeNodes": int(row["activeNodes"]),
                }
            )

    if not rows:
        sys.exit("ERROR: no parseable rows — check the CSV path and format")

    try:
        import matplotlib

        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        sys.exit("matplotlib not installed — run: pip install matplotlib")

    fig, (ax1, ax2) = plt.subplots(
        2, 1, figsize=(8, 6), sharex=True,
        gridspec_kw={"height_ratios": [3, 1]},
    )

    t = [r["t"] for r in rows]

    # ── Top panel: CPU utilisation ────────────────────────────────────────────
    ax1.plot(t, [r["avgDemand"] for r in rows],
             label="avg demand", linewidth=1.8, color="steelblue")
    ax1.plot(t, [r["p95Demand"] for r in rows],
             label="p95 demand", linestyle="--", linewidth=1.5, color="royalblue")
    ax1.plot(t, [r["maxNodeUtil"] for r in rows],
             label="peak node util", linestyle="-.", linewidth=1.5, color="darkorange")
    ax1.set_ylabel("CPU utilisation (%)")
    ax1.set_ylim(30, 105)  # floor at 30% — avgDemand never drops below ~40%
    ax1.legend(fontsize=8, ncol=2, loc="upper right")
    ax1.grid(True, alpha=0.3)
    ax1.set_title(
        "K8sPlanetLabExample — 30 nodes, 100 pods, 1 h sim, cpuMultiplier=4.5",
        fontsize=9,
    )

    # ── Bottom panel: pod and node counts ────────────────────────────────────
    max_pods = max(r["readyPods"] for r in rows)
    max_nodes = max(r["activeNodes"] for r in rows)

    ax2b = ax2.twinx()
    (l1,) = ax2.plot(
        t, [r["readyPods"] for r in rows],
        color="steelblue", label="ready pods", linewidth=1.8,
    )
    (l2,) = ax2b.plot(
        t, [r["activeNodes"] for r in rows],
        color="darkorange", linestyle="--", label="active nodes", linewidth=1.5,
    )
    ax2.set_xlabel("Simulation time (minutes)")
    ax2.set_ylabel("Ready pods", color="steelblue")
    ax2b.set_ylabel("Active nodes", color="darkorange")
    ax2.tick_params(axis="y", labelcolor="steelblue")
    ax2b.tick_params(axis="y", labelcolor="darkorange")
    # Fix y-ranges so flat lines read as "steady at capacity", not "no variation"
    ax2.set_ylim(0, max_pods * 1.1)
    ax2b.set_ylim(0, max_nodes * 1.1)
    ax2.legend(handles=[l1, l2], fontsize=8, loc="lower right")
    ax2.grid(True, alpha=0.3)

    fig.tight_layout()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.out, dpi=150, bbox_inches="tight")
    print(f"Saved → {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
