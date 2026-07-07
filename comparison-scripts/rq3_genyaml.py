#!/usr/bin/env python3
"""Generate a synthetic CloudSim Plus-K8s YAML for the RQ3 scalability sweep.

Usage:
    python rq3_genyaml.py --nodes 100 --pods 10000 > sweep-n100-p10000.yaml
"""
from __future__ import annotations

import argparse
import sys


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--nodes", type=int, required=True)
    ap.add_argument("--pods", type=int, required=True)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    out = sys.stdout
    out.write(f"# RQ3 sweep: nodes={args.nodes} pods={args.pods}\n")
    out.write("cluster:\n")
    out.write(f"  name: rq3-n{args.nodes}-p{args.pods}\n")
    out.write("  scheduler:\n    policy: COST_OPTIMIZED\n    k8sScoreScale: 0.01\n")
    out.write("  controllerTickIntervalSeconds: 1.0\n")
    out.write("  nodes:\n")
    for i in range(args.nodes):
        rack = f"r{i % 8}"
        zone = f"z{i % 4}"
        region = f"reg{i % 2}"
        out.write(
            f"    - {{ name: n{i:05d}, pes: 16, ramMiB: 32768, "
            f"rack: {rack}, zone: {zone}, region: {region}, costPerHour: 0.10 }}\n"
        )
    out.write("  workload:\n    deployments:\n")
    out.write(f"      - name: bench\n")
    out.write(f"        namespace: default\n")
    out.write(f"        replicas: {args.pods}\n")
    out.write("        cpuLoadProfile: STEADY_50\n")
    out.write("        container:\n")
    out.write("          image: registry.k8s.io/pause:3.9\n")
    out.write("          cpu: \"100m\"\n          memory: \"128Mi\"\n          length: 1000\n")


if __name__ == "__main__":
    main()
