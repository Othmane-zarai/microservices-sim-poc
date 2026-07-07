"""C1 matched-scoring analysis: LEAST_ALLOCATED sim vs kss baseline & real cluster.
Run AFTER deployment/rq1/run-la-fast.ps1 populates deployment/rq1/rq1-la-fresh/.
Reports honest agreement numbers; does not modify the paper."""
import csv, io, re, glob, os
from pathlib import Path

RQ1 = Path("deployment/rq1")
FRESH = RQ1 / "rq1-la-fresh"

SIM_RE = re.compile(r"^\s*(rq1-s\d{3})(?:-\d+)?\s+\S+\s+(worker-\S+)\s+\S+\s*(\S+)?", re.MULTILINE)

def norm(n):
    n = str(n).strip()
    return n[4:] if n.startswith("k3s-") else n

def read_any(p):
    raw = Path(p).read_bytes()
    if raw[:2] in (b"\xff\xfe", b"\xfe\xff"): return raw.decode("utf-16")
    if raw[:3] == b"\xef\xbb\xbf": return raw[3:].decode("utf-8", "replace")
    return raw.decode("utf-8", "replace")

# 1) LA winners from fresh logs
la = {}            # pod -> (winner, [candidates])
for log in sorted(FRESH.glob("sim-s*.log")):
    txt = read_any(log)
    for m in SIM_RE.finditer(txt):
        pod, node, cands = m.group(1), norm(m.group(2)), m.group(3)
        clist = [norm(c) for c in cands.split("|")] if cands else [node]
        la[pod] = (node, clist)

# 2) kss baseline (kss_node + CO sim_node) from kss-agreement.csv
kss = {}
with open(RQ1 / "kss-agreement.csv", encoding="utf-8-sig") as f:
    for r in csv.DictReader(f):
        kss[r["pod"]] = (norm(r["kss_node"]), norm(r["sim_node"]), r["category"])

# 3) real placements (one rq1 pod per scenario)
real = {}
for c in sorted(RQ1.glob("real-s*.csv")):
    sc = c.stem[len("real-"):]                # s000
    for row in read_any(c).strip().splitlines():
        if "," in row:
            pod, node = row.split(",", 1)
            if pod.startswith("rq1-"):
                real[pod] = norm(node)

pods = sorted(kss)                            # 300 rq1-s### keys
n = len(pods)
la_cov = sum(1 for p in pods if p in la)
print(f"scenarios with kss baseline = {n}; LA logs parsed = {la_cov}")

# CO baseline sanity (should reproduce paper 195/300 = 65.0%)
co_kss = sum(1 for p in pods if kss[p][1] == kss[p][0])
print(f"[sanity] CO sim vs kss   = {co_kss}/{n} = {100*co_kss/n:.1f}%  (paper: 195/300=65.0%)")

# LA vs kss
la_kss = sum(1 for p in pods if p in la and la[p][0] == kss[p][0])
print(f"[result] LA sim vs kss   = {la_kss}/{n} = {100*la_kss/n:.1f}%")

# placements changed CO -> LA
changed = [p for p in pods if p in la and la[p][0] != kss[p][1]]
print(f"[result] placements changed CO->LA = {len(changed)}/{n}")
if changed[:10]:
    print("  e.g.", [(p, kss[p][1], "->", la[p][0]) for p in changed[:8]])

# LA vs real (strict + combined via candidate set)
rp = [p for p in pods if p in real and p in la]
la_real = sum(1 for p in rp if la[p][0] == real[p])
la_real_comb = sum(1 for p in rp if real[p] in la[p][1])
print(f"[result] LA sim vs real  strict = {la_real}/{len(rp)} = {100*la_real/len(rp):.1f}%  "
      f"combined(score-set) = {la_real_comb}/{len(rp)} = {100*la_real_comb/len(rp):.1f}%  (CO real strict paper: 199/300=66.3%)")
