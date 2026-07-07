#!/usr/bin/env python3
"""RQ2b: VPA recommendation accuracy — simulator vs. real k3s VPA recommender.

Usage:
  python3 rq2b_vpa.py

Inputs (relative to repo root):
  deployment/rq2b/real-vpa-recommendations.csv
  deployment/rq2b/sim-vpa.csv

Output:
  deployment/rq2b/vpa-comparison.csv   (per-sample comparison)
  Prints summary to stdout.
"""

import csv, os, statistics

REPO = os.path.join(os.path.dirname(__file__), "..")
REAL = os.path.join(REPO, "deployment", "rq2b", "real-vpa-recommendations.csv")
SIM  = os.path.join(REPO, "deployment", "rq2b", "sim-vpa.csv")
OUT  = os.path.join(REPO, "deployment", "rq2b", "vpa-comparison.csv")

# ------------------------------------------------------------------
# Load real samples (drop empty rows and warm-up rows without target)
# ------------------------------------------------------------------
real_samples = []
with open(REAL) as f:
    for row in csv.DictReader(f):
        t = row.get("target_m", "").strip()
        if t and t.isdigit():
            real_samples.append(int(t))

# ------------------------------------------------------------------
# Load simulator recommendation
# ------------------------------------------------------------------
sim_target = None
with open(SIM) as f:
    for row in csv.DictReader(f):
        sim_target = int(row["target_m"])

if not real_samples or sim_target is None:
    print("ERROR: missing data")
    raise SystemExit(1)

# ------------------------------------------------------------------
# Statistics
# ------------------------------------------------------------------
real_mean   = statistics.mean(real_samples)
real_stdev  = statistics.stdev(real_samples) if len(real_samples) > 1 else 0
abs_error   = abs(sim_target - real_mean)
rel_error   = abs_error / real_mean * 100
nrmsd       = (abs_error / real_mean) * 100   # same as rel_error for single sim value

# ------------------------------------------------------------------
# Per-sample CSV
# ------------------------------------------------------------------
with open(OUT, "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["sample", "real_target_m", "sim_target_m", "abs_error_m", "rel_error_pct"])
    for i, rt in enumerate(real_samples, 1):
        w.writerow([i, rt, sim_target, abs(sim_target - rt), f"{abs(sim_target - rt)/rt*100:.2f}"])

print("=" * 60)
print("RQ2b: VPA Recommendation Accuracy")
print("=" * 60)
print(f"  Workload       : 3 pods, CPU limit 500m, 100% utilization")
print(f"  Real cluster   : k3s v1.35.4, VPA recommender v1.6.0")
print(f"  VPA mode       : Off (recommendations only)")
print(f"  Samples        : {len(real_samples)} × 30s = {len(real_samples)*30}s")
print(f"  Real target    : {real_mean:.0f}m  (stdev = {real_stdev:.1f}m)")
print(f"  Sim target     : {sim_target}m  (load=100%, sim-target=85%)")
print(f"  Abs error      : {abs_error:.1f}m")
print(f"  Rel error      : {rel_error:.2f}%")
print(f"  NRMSD          : {nrmsd:.2f}%")
print(f"  Status         : {'PASS (<5%)' if rel_error < 5.0 else 'FAIL'}")
print(f"\n  Written: {OUT}")
