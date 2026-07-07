#!/usr/bin/env python3
"""Compute NRMSD on HPA pod-count and node-count trajectories for RQ2.

Reads:
  deployment/rq2/real-hpa.csv              (timestamp, current, min, max, desired, cpuPct)
  deployment/rq2/real-node-utilization.csv
  deployment/rq2/sim-hpa.txt               (grep'd from sim-scenario.log)
  deployment/rq2/sim-ca-events.txt         (grep'd from sim-scenario.log)
  deployment/rq2/sim-scenario.log          (fallback: extract initial node count from Knobs)

Metrics reported:
  pod_count_nrmsd   — NRMSD on the scale-up trajectory (convergence-time aligned)
  node_count_mae    — MAE on node counts throughout the run
  steady_state_err  — absolute difference in final equilibrium replica count

Time alignment:
  Sim events are forward-filled onto the real-side time grid.  Because the
  simulator compresses the scale-up phase (e.g. 7 sim-s vs 80 real-s), we
  apply convergence-time normalisation: the sim's last HPA event is mapped to
  the real time at which desired first reaches its final value, then all
  earlier events are scaled proportionally.

Usage:
    python rq2_nrmsd.py deployment/rq2 > deployment/rq2/nrmsd.csv
"""
from __future__ import annotations

import csv
import math
import re
import sys
from datetime import datetime
from pathlib import Path


def read_real_hpa_timed(p: Path) -> tuple[list[int], list[float]]:
    """Return (desired_list, elapsed_seconds_list) from real-hpa.csv."""
    rows: list[tuple[datetime, int]] = []
    for line in p.read_text(encoding="utf-8").splitlines():
        cols = line.split(",")
        if len(cols) >= 5 and cols[4].strip().isdigit():
            try:
                ts = datetime.fromisoformat(cols[0].strip())
                rows.append((ts, int(cols[4].strip())))
            except ValueError:
                continue
    if not rows:
        return [], []
    t0 = rows[0][0]
    return (
        [d for _, d in rows],
        [(ts - t0).total_seconds() for ts, _ in rows],
    )


def read_sim_hpa_events(p: Path) -> list[tuple[float, int]]:
    """Extract (sim_time, target_replicas) from HPA scaling lines.

    Matches: INFO  2.00: HPA 'web-hpa': avg CPU=100.0%, replicas 2 -> 4
    Returns events sorted by time; does NOT include the initial baseline.
    """
    event_re = re.compile(r"INFO\s+([\d.]+):\s+HPA.*replicas\s+\d+\s*->\s*(\d+)")
    events: list[tuple[float, int]] = []
    for line in p.read_text(encoding="utf-8").splitlines():
        m = event_re.search(line)
        if m:
            events.append((float(m.group(1)), int(m.group(2))))
    return sorted(events)


def build_aligned_sim(
    sim_events: list[tuple[float, int]],
    real_elapsed: list[float],
    real_desired: list[int],
    initial_desired: int = 2,
) -> list[int]:
    """Forward-fill sim HPA events onto the real time grid.

    Convergence-time normalisation: the sim's last HPA event is scaled so it
    falls at the real time when desired first reaches its final value.  All
    earlier sim events scale proportionally.  This accounts for the simulator
    compressing the workload ramp-up into fewer simulated seconds.
    """
    if not sim_events:
        return [initial_desired] * len(real_elapsed)

    final_desired = sim_events[-1][1]
    sim_convergence = sim_events[-1][0]

    # Real convergence: first timestamp where desired equals the sim's final value
    real_convergence = real_elapsed[-1]  # fallback: end of experiment
    for elapsed, desired in zip(real_elapsed, real_desired):
        if desired >= final_desired:
            real_convergence = elapsed
            break

    scale = real_convergence / sim_convergence if sim_convergence > 0 else 1.0

    # Build time-scaled event list; prepend initial state at t=0
    scaled: list[tuple[float, int]] = [(0.0, initial_desired)] + [
        (t * scale, d) for t, d in sim_events
    ]

    # Forward-fill
    result: list[int] = []
    current = initial_desired
    ei = 0
    for t_real in real_elapsed:
        while ei < len(scaled) and scaled[ei][0] <= t_real:
            current = scaled[ei][1]
            ei += 1
        result.append(current)
    return result


def read_real_hpa(p: Path) -> list[int]:
    """Return list of `desired` (column index 4) from real-hpa.csv (no timestamps)."""
    out: list[int] = []
    for line in p.read_text(encoding="utf-8").splitlines():
        cols = line.split(",")
        if len(cols) >= 5 and cols[4].strip().isdigit():
            out.append(int(cols[4].strip()))
    return out


def read_real_nodes(p: Path) -> list[int]:
    """Distinct-node count over time (one entry per sample timestamp)."""
    by_ts: dict[str, set[str]] = {}
    for line in p.read_text(encoding="utf-8").splitlines():
        cols = line.split(",")
        if len(cols) >= 2:
            by_ts.setdefault(cols[0], set()).add(cols[1])
    return [len(s) for s in by_ts.values()]


def read_sim_nodes(ca_path: Path, log_path: Path | None, real_len: int) -> list[int]:
    """Return sim node-count series.

    Priority:
    1. ``pool size now N`` events from sim-ca-events.txt (CA provisioned nodes)
    2. Legacy ``nodes=N`` markers in ca-events file
    3. Seed-node count from the ``nodes=N`` Knobs line in sim-scenario.log,
       returned as a constant series of length real_len (no CA events fired).
    """
    text = ca_path.read_text(encoding="utf-8")

    events = [int(m.group(1)) for m in re.finditer(r"pool size now (\d+)", text)]
    if not events:
        events = [int(m.group(1)) for m in re.finditer(r"nodes[= ]+(\d+)", text)]

    # Fallback: CA never fired → cluster kept its seed nodes throughout.
    if not events and log_path and log_path.is_file():
        log_text = log_path.read_text(encoding="utf-8")
        # Match "nodes=N" only in the Knobs line (not in other log lines)
        m = re.search(r"Knobs:.*\bnodes=(\d+)", log_text)
        if m:
            return [int(m.group(1))] * real_len

    return events


def nrmsd(a: list[float], b: list[float]) -> float:
    """Normalised RMSD on the zipped prefix; NaN if either is empty."""
    if not a or not b:
        return float("nan")
    n = min(len(a), len(b))
    a, b = a[:n], b[:n]
    rmsd = math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)) / n)
    rng = max(a + b) - min(a + b)
    return rmsd / rng if rng else 0.0


def mae(a: list[float], b: list[float]) -> float:
    if not a or not b:
        return float("nan")
    n = min(len(a), len(b))
    return sum(abs(x - y) for x, y in zip(a[:n], b[:n])) / n


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit("usage: rq2_nrmsd.py <rq2-dir>")
    rq2 = Path(sys.argv[1])

    # Pod-count: use timed alignment for NRMSD, plain list for steady-state
    real_pods, real_elapsed = read_real_hpa_timed(rq2 / "real-hpa.csv")
    sim_hpa_events = read_sim_hpa_events(rq2 / "sim-hpa.txt")

    # Determine initial baseline (replicas before first HPA event)
    initial_desired = real_pods[0] if real_pods else 2

    # Build time-aligned sim series (same length as real)
    sim_pods_aligned = build_aligned_sim(
        sim_hpa_events, real_elapsed, real_pods, initial_desired
    )

    real_nodes = read_real_nodes(rq2 / "real-node-utilization.csv")
    sim_nodes  = read_sim_nodes(
        rq2 / "sim-ca-events.txt",
        rq2 / "sim-scenario.log",
        len(real_nodes),
    )

    pod_nrmsd    = nrmsd([float(x) for x in real_pods],
                         [float(x) for x in sim_pods_aligned])
    node_mae_val = mae([float(x) for x in real_nodes],
                       [float(x) for x in sim_nodes])

    # Steady-state: last 20% of real series vs simulator's final equilibrium value
    ss_window        = max(1, len(real_pods) // 5)
    real_ss          = round(sum(real_pods[-ss_window:]) / ss_window)
    sim_ss           = sim_hpa_events[-1][1] if sim_hpa_events else initial_desired
    steady_state_err = abs(real_ss - sim_ss)

    w = csv.writer(sys.stdout)
    w.writerow(["metric", "real_n", "sim_n", "value"])
    w.writerow(["pod_count_nrmsd",       len(real_pods),  len(sim_pods_aligned),  f"{pod_nrmsd:.4f}"])
    w.writerow(["node_count_mae",        len(real_nodes), len(sim_nodes),         f"{node_mae_val:.4f}"])
    w.writerow(["steady_state_pods_err", ss_window,       1,                      f"{steady_state_err:.1f}"])

    print(f"\nRQ2 results:", file=sys.stderr)
    print(f"  Pod-count NRMSD (convergence-aligned):  {pod_nrmsd:.4f}  (target <0.15)", file=sys.stderr)
    print(f"  Node-count MAE:                         {node_mae_val:.4f}   (target ≤1.0)", file=sys.stderr)
    print(f"  Steady-state replica error:             {steady_state_err:.1f} pods  (real={real_ss}, sim={sim_ss})", file=sys.stderr)


if __name__ == "__main__":
    main()
