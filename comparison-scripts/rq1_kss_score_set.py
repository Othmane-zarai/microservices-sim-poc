#!/usr/bin/env python3
"""Compute combined winner-or-score-set agreement between kss placements
and the simulator, re-using the score-set definition from rq1_agreement.py.

Reads the existing kss-agreement.csv (per-pod kss winner) and the existing
sim-*.log files (which carry the simulator's tied-minimum-score candidate
set per pod). A pod is counted as agreeing under the combined metric when
kss's winner equals the sim's winner OR falls inside the sim's score set.

Writes one CSV row per pod with columns:
    pod, kss_node, sim_node, sim_score_set, category

where category is one of MATCH_WINNER, MATCH_SCORE_SET, DIFFER,
ONLY_KSS, ONLY_SIM. Prints headline numbers (strict, score-set,
combined) on stderr.
"""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from rq1_agreement import parse_sim, _normalise_node  # noqa: E402


def load_kss_csv(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    with path.open(encoding="utf-8") as fp:
        reader = csv.DictReader(fp)
        for row in reader:
            pod = row["pod"]
            kss_node = _normalise_node(row["kss_node"])
            out[pod] = kss_node
    return out


def load_sim_with_candidates(rq1_dir: Path) -> dict[str, tuple[str, list[str]]]:
    out: dict[str, tuple[str, list[str]]] = {}
    for log in sorted(rq1_dir.glob("sim-*.log")):
        for pod, (node, cands) in parse_sim(log).items():
            out[pod] = (_normalise_node(node), [_normalise_node(c) for c in cands])
    return out


def classify(kss_node: str, sim_node: str, sim_candidates: list[str]) -> str:
    if not kss_node and not sim_node:
        return "BOTH_PENDING"
    if not kss_node:
        return "ONLY_SIM"
    if not sim_node:
        return "ONLY_KSS"
    if kss_node == sim_node:
        return "MATCH_WINNER"
    if kss_node in sim_candidates:
        return "MATCH_SCORE_SET"
    return "DIFFER"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rq1-dir", required=True, type=Path,
                    help="deployment/rq1 directory (must contain sim-*.log files)")
    ap.add_argument("--kss-agreement", required=True, type=Path,
                    help="kss-agreement.csv produced by rq1_kss_compare.py")
    ap.add_argument("--out", required=True, type=Path,
                    help="output CSV with combined classification")
    args = ap.parse_args()

    kss = load_kss_csv(args.kss_agreement)
    sim = load_sim_with_candidates(args.rq1_dir)

    pods = sorted(set(kss) | set(sim))
    rows = []
    counts: dict[str, int] = {}
    for pod in pods:
        kss_node = kss.get(pod, "")
        sim_node, cands = sim.get(pod, ("", []))
        cat = classify(kss_node, sim_node, cands)
        counts[cat] = counts.get(cat, 0) + 1
        rows.append((pod, kss_node, sim_node, "|".join(cands), cat))

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.writer(fp)
        writer.writerow(["pod", "kss_node", "sim_node", "sim_score_set", "category"])
        for row in rows:
            writer.writerow(row)

    total = len(pods)
    strict = counts.get("MATCH_WINNER", 0)
    score_set = counts.get("MATCH_SCORE_SET", 0)
    combined = strict + score_set
    diff = counts.get("DIFFER", 0)

    sys.stderr.write(f"total pods            : {total}\n")
    for cat in ("MATCH_WINNER", "MATCH_SCORE_SET", "DIFFER",
                "ONLY_KSS", "ONLY_SIM", "BOTH_PENDING"):
        if cat in counts:
            sys.stderr.write(f"  {cat:<16}  : {counts[cat]}\n")
    if total:
        sys.stderr.write(f"strict winner agree  : {strict}/{total} "
                         f"= {strict/total*100:.2f}%\n")
        sys.stderr.write(f"combined (W or SS)   : {combined}/{total} "
                         f"= {combined/total*100:.2f}%\n")
        sys.stderr.write(f"strict disagreement  : {diff}/{total} "
                         f"= {diff/total*100:.2f}%\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
