#!/usr/bin/env python3
"""Compute placement-agreement % between real cluster and simulator for RQ1.

Reads paired (real-<scenario>.csv, sim-<scenario>.log) files from
deployment/rq1/, classifies each disagreement, and writes summary.csv.

Usage:
    python rq1_agreement.py deployment/rq1 > deployment/rq1/summary.csv
"""
from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

# Pull "POD ... NODE ..." rows from the simulator placement table. The line
# format is "  <pod>  <namespace>  <node>  <rack>" with leading whitespace, so
# allow optional leading spaces. Strip the "-<replicaIdx>" suffix the simulator
# appends (e.g. "rq1-s000-0" → "rq1-s000") so the key matches the real Pod name.
SIM_PLACEMENT_RE = re.compile(
    r"^\s*(?P<pod>rq1-s\d{3})(?:-\d+)?\s+\S+\s+(?P<node>worker-\S+)\s+\S+\s+(?P<candidates>\S+)?",
    re.MULTILINE,
)


def _normalise_node(name: str) -> str:
    """Strip the k3s `k3s-` prefix so real `k3s-worker-3` matches sim `worker-3`."""
    if not name:
        return name
    if name.startswith("k3s-"):
        return name[len("k3s-"):]
    return name


def parse_real(csv_path: Path) -> dict[str, str]:
    """real-<scenario>.csv → {podName: nodeName}."""
    rows = csv_path.read_text(encoding="utf-8").strip().splitlines()
    out: dict[str, str] = {}
    for row in rows:
        if "," not in row:
            continue
        pod, node = row.split(",", 1)
        out[pod] = _normalise_node(node)
    return out


def _read_sim_log(log_path: Path) -> str:
    """Read the simulator log, auto-detecting the encoding.

    PowerShell `>` redirection on Windows writes UTF-16 LE with a BOM by
    default; Tee-Object writes UTF-8 with a BOM. Native Python file I/O
    won't auto-detect either, so we sniff the BOM and pick the matching
    decoder.
    """
    raw = log_path.read_bytes()
    if raw.startswith(b"\xff\xfe"):
        return raw.decode("utf-16-le", errors="replace")
    if raw.startswith(b"\xfe\xff"):
        return raw.decode("utf-16-be", errors="replace")
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw[3:].decode("utf-8", errors="replace")
    return raw.decode("utf-8", errors="replace")


def parse_sim(log_path: Path) -> dict[str, tuple[str, list[str]]]:
    text = _read_sim_log(log_path)
    out = {}
    for m in SIM_PLACEMENT_RE.finditer(text):
        cands_str = m.group("candidates")
        cands = cands_str.split("|") if cands_str else [m.group("node")]
        out[m.group("pod")] = (m.group("node"), cands)
    return out


def classify(real_node: str | None, sim_node: str | None, sim_candidates: list[str]) -> str:
    if real_node == sim_node:
        return "MATCH_WINNER"
    if real_node is None:
        return "ONLY_IN_SIM"
    if sim_node is None:
        return "ONLY_IN_REAL"
    if real_node in sim_candidates:
        return "MATCH_SCORE_SET"
    if real_node.startswith("worker-") and sim_node.startswith("worker-"):
        return "WRONG_NODE"
    return "NAME_MISMATCH"


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit("usage: rq1_agreement.py <rq1-dir>")
    rq1_dir = Path(sys.argv[1])

    writer = csv.writer(sys.stdout)
    writer.writerow(["scenario", "pod", "real_node", "sim_node", "category"])

    matches = total = 0
    by_category: dict[str, int] = {}
    for real_csv in sorted(rq1_dir.glob("real-*.csv")):
        scenario = real_csv.stem[len("real-"):]
        sim_log = rq1_dir / f"sim-{scenario}.log"
        if not sim_log.exists():
            print(f"WARN: missing {sim_log}", file=sys.stderr)
            continue
        real = parse_real(real_csv)
        sim = parse_sim(sim_log)
        for pod in sorted(set(real) | set(sim)):
            sim_node, sim_cands = sim.get(pod, (None, []))
            cat = classify(real.get(pod), sim_node, sim_cands)
            writer.writerow([scenario, pod, real.get(pod, ""), sim_node or "", cat])
            total += 1
            by_category[cat] = by_category.get(cat, 0) + 1
            if cat.startswith("MATCH"):
                matches += 1

    pct = (matches / total * 100) if total else 0
    print(
        f"\nRQ1 placement agreement: {matches}/{total} = {pct:.1f}%",
        file=sys.stderr,
    )
    for cat, n in sorted(by_category.items()):
        print(f"  {cat}: {n}", file=sys.stderr)


if __name__ == "__main__":
    main()
