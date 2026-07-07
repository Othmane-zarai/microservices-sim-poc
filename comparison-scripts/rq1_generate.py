#!/usr/bin/env python3
"""Generate paired (real-manifest, simulator-YAML) inputs for RQ1.

For RQ1 we need N scenarios where each scenario is an isolated pod with a
deterministic mix of nodeSelector / required nodeAffinity / tolerations,
applied to both the real cluster (3 workers labelled per
EMPIRICAL_VALIDATION.md §0.2) and the simulator (matching 3-node YAML).

Usage:
    python rq1_generate.py --count 100 \\
        --out-real deployment/rq1/real-manifests \\
        --out-sim  deployment/rq1/scenarios \\
        --seed 42
"""
from __future__ import annotations

import argparse
import os
import random
from pathlib import Path

# Worker labels expected in EMPIRICAL_VALIDATION.md §0.2.
WORKERS = [
    {"name": "worker-1", "role": "general", "zone": "a", "rack": "r1", "region": "oci-1"},
    {"name": "worker-2", "role": "general", "zone": "b", "rack": "r2", "region": "oci-1"},
    {"name": "worker-3", "role": "gpu",     "zone": "c", "rack": "r3", "region": "oci-1",
     "taint": {"key": "dedicated", "value": "gpu", "effect": "NoSchedule"}},
]
LABEL_KEYS = ["role", "zone", "rack"]
ROLE_VALUES = ["general", "gpu"]
ZONE_VALUES = ["a", "b", "c"]
RACK_VALUES = ["r1", "r2", "r3"]
SCENARIO_TYPES = ["nodeSelector", "requiredAffinity", "tolerationOnly", "untargeted"]


# Labels of the tainted worker (worker-3) — a pod targeting any of these
# must also carry the matching toleration or it will stay Pending forever.
TAINTED_NODE_LABELS = {"role": "gpu", "zone": "c", "rack": "r3"}
GPU_TOLERATION = (
    "  tolerations:\n"
    "    - key: dedicated\n"
    "      operator: Equal\n"
    "      value: gpu\n"
    "      effect: NoSchedule\n"
)


def _needs_gpu_toleration(key: str, val: str) -> bool:
    """A nodeSelector/affinity targeting worker-3 must tolerate its taint."""
    return TAINTED_NODE_LABELS.get(key) == val


def make_real_manifest(name: str, scenario_type: str, rng: random.Random) -> str:
    """Return a Kubernetes manifest YAML for one RQ1 scenario."""
    spec_extra = ""
    if scenario_type == "nodeSelector":
        key = rng.choice(LABEL_KEYS)
        val = _value_for_key(key, rng)
        spec_extra = f"  nodeSelector:\n    {key}: \"{val}\"\n"
        if _needs_gpu_toleration(key, val):
            spec_extra += GPU_TOLERATION
    elif scenario_type == "requiredAffinity":
        key = rng.choice(LABEL_KEYS)
        val = _value_for_key(key, rng)
        spec_extra = (
            "  affinity:\n"
            "    nodeAffinity:\n"
            "      requiredDuringSchedulingIgnoredDuringExecution:\n"
            "        nodeSelectorTerms:\n"
            "          - matchExpressions:\n"
            f"            - key: {key}\n"
            "              operator: In\n"
            f"              values: [\"{val}\"]\n"
        )
        if _needs_gpu_toleration(key, val):
            spec_extra += GPU_TOLERATION
    elif scenario_type == "tolerationOnly":
        spec_extra = GPU_TOLERATION
    return (
        "apiVersion: v1\n"
        "kind: Pod\n"
        "metadata:\n"
        f"  name: rq1-{name}\n"
        "  labels:\n"
        f"    rq1-scenario: \"{name}\"\n"
        "spec:\n"
        "  containers:\n"
        "    - name: pause\n"
        "      image: registry.k8s.io/pause:3.9\n"
        "      resources:\n"
        "        requests: {cpu: 50m, memory: 32Mi}\n"
        f"{spec_extra}"
    )


def make_sim_yaml(name: str, real_manifest_yaml: str, scenario_seed: int,
                  policy: str = "COST_OPTIMIZED") -> str:
    """Build the matching simulator YAML — 3 nodes labelled like the real cluster."""
    nodes = []
    for w in WORKERS:
        labels = {"role": w["role"], "zone": w["zone"], "rack": w["rack"], "region": w["region"]}
        labels_str = ", ".join(f"{k}: {v}" for k, v in labels.items())
        node = (
            f"    - name: {w['name']}\n"
            f"      pes: 8\n"
            f"      ramMiB: 8192\n"
            f"      rack: {w['rack']}\n"
            f"      zone: oci-1-{w['zone']}\n"
            f"      region: oci-1\n"
            f"      costPerHour: 0.10\n"
            f"      labels: {{ {labels_str} }}\n"
        )
        if "taint" in w:
            t = w["taint"]
            node += (
                f"      taints:\n"
                f"        - {{ key: {t['key']}, value: {t['value']}, effect: NO_SCHEDULE }}\n"
            )
        nodes.append(node)
    nodes_block = "".join(nodes)
    # Background pods mirror the persistent workloads that occupy worker-1
    # and worker-2 on the real k3s cluster (web replicas, locust-driver), so
    # the simulator's NodeResourcesLeastAllocated scoring sees the same
    # disparity the real scheduler does. Without these, all sim workers are
    # equally idle and LeastAllocated ties them.
    background_block = _background_workload_block()
    deployment_block = _translate_real_to_sim_workload(name, real_manifest_yaml)
    return (
        f"# RQ1 scenario: {name} (matches real-manifests/{name}.yaml)\n"
        f"# scenario_seed: {scenario_seed}\n"
        "cluster:\n"
        f"  name: rq1-{name}\n"
        "  scheduler:\n"
        f"    policy: {policy}\n"
        "    k8sScoreScale: 0.05\n"
        "  controllerTickIntervalSeconds: 1.0\n"
        "  nodes:\n"
        f"{nodes_block}"
        "  workload:\n"
        "    deployments:\n"
        f"{background_block}"
        f"{deployment_block}"
    )


def _background_workload_block() -> str:
    """Background pods listed first so they occupy worker-1 / worker-2 before
    the RQ1 test pod is scheduled. None tolerate the gpu taint, so the
    simulator's NoSchedule filter keeps them off worker-3 (matching real)."""
    return (
        "      - name: bg-web-a\n"
        "        namespace: default\n"
        "        replicas: 1\n"
        "        labels: { app: bg-web }\n"
        "        cpuLoadProfile: IDLE_15\n"
        "        container:\n"
        "          image: nginx:alpine\n"
        "          cpu: \"100m\"\n"
        "          memory: \"64Mi\"\n"
        "          length: 1000000\n"
        "      - name: bg-locust\n"
        "        namespace: default\n"
        "        replicas: 1\n"
        "        labels: { app: bg-locust }\n"
        "        cpuLoadProfile: IDLE_15\n"
        "        container:\n"
        "          image: locustio/locust\n"
        "          cpu: \"100m\"\n"
        "          memory: \"64Mi\"\n"
        "          length: 1000000\n"
    )


def _translate_real_to_sim_workload(name: str, real_yaml: str) -> str:
    """Mirror the real Pod's nodeSelector / affinity / tolerations into a 1-replica Deployment.

    The deployment is named rq1-<name> (not just <name>) so the simulator
    emits pods like 'rq1-<name>-<idx>' that match the real pod's 'rq1-<name>'
    prefix, allowing the agreement script's regex to pair them.
    """
    block = (
        f"      - name: rq1-{name}\n"
        "        namespace: default\n"
        "        replicas: 1\n"
        f"        labels: {{ rq1-scenario: {name} }}\n"
        "        cpuLoadProfile: IDLE_15\n"
    )
    if "nodeSelector:" in real_yaml:
        # Reuse the same key/val from the real manifest.
        for line in real_yaml.splitlines():
            line = line.strip()
            if line and ":" in line and line not in ("nodeSelector:",):
                if line.startswith(("role:", "zone:", "rack:", "region:")):
                    key, val = line.split(":", 1)
                    block += f"        nodeAffinity:\n          required:\n"
                    block += (
                        f"            - {{ key: {key.strip()}, operator: In, "
                        f"values: [{val.strip().strip(chr(34))}] }}\n"
                    )
                    break
    elif "requiredDuringSchedulingIgnoredDuringExecution" in real_yaml:
        for line in real_yaml.splitlines():
            line = line.strip()
            if line.startswith("- key:"):
                key = line.split(":", 1)[1].strip()
            elif line.startswith("values:"):
                val = line.split(":", 1)[1].strip().strip("[]\"")
                block += f"        nodeAffinity:\n          required:\n"
                block += f"            - {{ key: {key}, operator: In, values: [{val}] }}\n"
                break
    if "tolerations:" in real_yaml:
        block += (
            "        tolerations:\n"
            "          - { key: dedicated, operator: Equal, value: gpu, effect: NO_SCHEDULE }\n"
        )
    block += (
        "        container:\n"
        "          image: registry.k8s.io/pause:3.9\n"
        "          cpu: \"50m\"\n"
        "          memory: \"32Mi\"\n"
        "          length: 100\n"
    )
    return block


def _value_for_key(key: str, rng: random.Random) -> str:
    return {
        "role": rng.choice(ROLE_VALUES),
        "zone": rng.choice(ZONE_VALUES),
        "rack": rng.choice(RACK_VALUES),
    }[key]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=300)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--weights", type=str, default="1,1,1,1",
                    help="Comma-separated weights for [nodeSelector, requiredAffinity, tolerationOnly, untargeted]")
    ap.add_argument("--policy", type=str, default="COST_OPTIMIZED",
                    help="Scheduler scoring policy written into each sim YAML "
                         "(COST_OPTIMIZED | LEAST_ALLOCATED | LATENCY_AWARE | ...)")
    ap.add_argument("--out-real", required=True, type=Path)
    ap.add_argument("--out-sim", required=True, type=Path)
    args = ap.parse_args()

    weights = [float(x.strip()) for x in args.weights.split(",")]
    if len(weights) != len(SCENARIO_TYPES):
        ap.error(f"Expected {len(SCENARIO_TYPES)} weights for {SCENARIO_TYPES}")

    args.out_real.mkdir(parents=True, exist_ok=True)
    args.out_sim.mkdir(parents=True, exist_ok=True)
    rng = random.Random(args.seed)

    for i in range(args.count):
        name = f"s{i:03d}"
        scenario_seed = rng.randint(0, 2**32 - 1)
        scenario_rng = random.Random(scenario_seed)
        
        scenario_type = scenario_rng.choices(SCENARIO_TYPES, weights=weights, k=1)[0]
        real_yaml = f"# scenario_seed: {scenario_seed}\n" + make_real_manifest(name, scenario_type, scenario_rng)
        sim_yaml = make_sim_yaml(name, real_yaml, scenario_seed, policy=args.policy)
        
        (args.out_real / f"{name}.yaml").write_text(real_yaml, encoding="utf-8")
        (args.out_sim / f"{name}.yaml").write_text(sim_yaml, encoding="utf-8")

    print(f"Generated {args.count} scenario pairs in {args.out_real} / {args.out_sim}")


if __name__ == "__main__":
    main()
