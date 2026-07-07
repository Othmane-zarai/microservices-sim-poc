#!/usr/bin/env python3
"""Run the RQ3 scalability sweep with proper statistical methodology.

Reviewer concern MC8: the published RQ3 wall times were single-shot
measurements. JVM microbenchmarks at this scale need warm-up runs
plus replicated measurements with mean ± std and 95 % confidence
intervals reported.

This driver:

  1. Builds a synthetic YAML for each (n, p) cell via
     ``rq3_genyaml.py``.
  2. Runs ``--warmup`` discarded executions to warm the JIT / class
     loaders / page cache.
  3. Runs ``--reps`` measured executions; records each wall-clock
     time.
  4. Aggregates to mean ± std + 95 % CI using Student's t critical
     value for the chosen reps.

Output schema (``scalability-replicated.csv``):

    nodes,pods,reps,mean_ms,stdev_ms,ci95_low_ms,ci95_high_ms,raw_ms

``raw_ms`` is the pipe-separated raw measurements for full
reproducibility.

Usage::

    python comparison-scripts/rq3_replicate.py \\
        --jar target/csp-examples-springboot-1.0.0-SNAPSHOT.jar \\
        --out-dir deployment/rq3 \\
        --out-csv deployment/rq3/scalability-replicated.csv \\
        --cells "10:100,10:500,10:1000,100:100,1000:100" \\
        --warmup 3 --reps 10
"""
from __future__ import annotations

import argparse
import csv
import math
import os
import statistics
import subprocess
import sys
import time
from pathlib import Path

# Student's t two-sided critical values for 95% CI at small reps
# (df = reps - 1). Looked up from a standard table.
T95 = {
    1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571,
    6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228,
    14: 2.145, 19: 2.093, 29: 2.045, 49: 2.010,
}


def t_critical(reps: int) -> float:
    """Two-sided 95% Student's t critical value (df = reps-1)."""
    df = max(1, reps - 1)
    return T95.get(df, 1.96)  # large-sample fallback


def gen_yaml(genyaml: Path, nodes: int, pods: int, out_yaml: Path) -> None:
    proc = subprocess.run(
        [sys.executable, str(genyaml), "--nodes", str(nodes), "--pods", str(pods)],
        check=True, capture_output=True, text=True,
    )
    out_yaml.write_text(proc.stdout, encoding="utf-8")


def run_one(jar: Path, yaml: Path, duration: int) -> float:
    """Invoke the simulator once; return wall-clock seconds."""
    env = os.environ.copy()
    env["JAVA_TOOL_OPTIONS"] = "-Xmx12g"
    t0 = time.perf_counter()
    proc = subprocess.run(
        [
            "java",
            f"-Dk8syaml.config={yaml}",
            f"-Dk8syaml.duration={duration}",
            "-Dk8s.benchmark=true",
            "-Dspring.main.banner-mode=off",
            "-Dlogging.level.root=ERROR",
            "-jar", str(jar),
            "--example=K8sClusterFromYamlExample",
        ],
        env=env,
        capture_output=True,
        text=True,
        check=False,
        encoding="utf-8",
        errors="replace",
    )
    dt = time.perf_counter() - t0
    if proc.returncode != 0:
        sys.stderr.write(f"  ! sim returncode={proc.returncode}\n")
        sys.stderr.write(f"  stderr tail:\n{proc.stderr[-500:]}\n")
    return dt


def parse_cells(spec: str) -> list[tuple[int, int]]:
    out: list[tuple[int, int]] = []
    for tok in spec.split(","):
        tok = tok.strip()
        if not tok:
            continue
        n, _, p = tok.partition(":")
        out.append((int(n), int(p)))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jar", type=Path, required=True,
                    help="path to the csp-examples-springboot jar")
    ap.add_argument("--genyaml", type=Path,
                    default=Path(__file__).with_name("rq3_genyaml.py"),
                    help="path to rq3_genyaml.py")
    ap.add_argument("--out-dir", type=Path, required=True,
                    help="directory for generated YAMLs")
    ap.add_argument("--out-csv", type=Path, required=True,
                    help="output replicated-results CSV")
    ap.add_argument("--cells", default="10:100,10:500,10:1000,100:100,1000:100",
                    help="comma-separated nodes:pods cells")
    ap.add_argument("--warmup", type=int, default=3,
                    help="warm-up runs to discard per cell")
    ap.add_argument("--reps", type=int, default=10,
                    help="measured runs per cell")
    ap.add_argument("--duration", type=int, default=30,
                    help="simulated seconds per run")
    args = ap.parse_args()

    if not args.jar.is_file():
        ap.error(f"jar not found: {args.jar}")
    if not args.genyaml.is_file():
        ap.error(f"genyaml not found: {args.genyaml}")

    args.out_dir.mkdir(parents=True, exist_ok=True)
    args.out_csv.parent.mkdir(parents=True, exist_ok=True)

    cells = parse_cells(args.cells)
    tcrit = t_critical(args.reps)

    with args.out_csv.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.writer(fp)
        writer.writerow([
            "nodes", "pods", "reps",
            "mean_ms", "stdev_ms",
            "ci95_low_ms", "ci95_high_ms",
            "raw_ms",
        ])

        for n, p in cells:
            yaml_path = args.out_dir / f"sweep-n{n}-p{p}.yaml"
            gen_yaml(args.genyaml, n, p, yaml_path)
            sys.stderr.write(f"[rq3] n={n} p={p} warmup={args.warmup} reps={args.reps}\n")

            for w in range(args.warmup):
                dt = run_one(args.jar, yaml_path, args.duration)
                sys.stderr.write(f"  warm {w + 1}/{args.warmup}: {dt * 1000:.0f} ms (discard)\n")

            samples: list[float] = []
            for r in range(args.reps):
                dt = run_one(args.jar, yaml_path, args.duration)
                samples.append(dt * 1000.0)
                sys.stderr.write(f"  rep  {r + 1}/{args.reps}: {dt * 1000:.0f} ms\n")

            mean = statistics.mean(samples)
            std = statistics.stdev(samples) if len(samples) > 1 else 0.0
            margin = tcrit * std / math.sqrt(len(samples)) if std else 0.0
            ci_low, ci_high = mean - margin, mean + margin

            writer.writerow([
                n, p, args.reps,
                f"{mean:.1f}", f"{std:.1f}",
                f"{ci_low:.1f}", f"{ci_high:.1f}",
                "|".join(f"{s:.1f}" for s in samples),
            ])
            sys.stderr.write(
                f"[rq3] n={n} p={p}: {mean:.0f} ± {std:.0f} ms "
                f"(95% CI [{ci_low:.0f}, {ci_high:.0f}])\n"
            )

    sys.stderr.write(f"[rq3] wrote {args.out_csv}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
