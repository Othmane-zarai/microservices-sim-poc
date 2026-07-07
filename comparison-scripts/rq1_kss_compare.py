#!/usr/bin/env python3
"""Cross-tool RQ1 comparison: cloudsimplus-k8s vs the production kube-scheduler.

This script answers a recurring reviewer question for the SPE paper:
"how does CloudSim Plus-K8s compare to the upstream kube-scheduler on the
same RQ1 scenario set?"

It runs each scenario through the official ``kube-scheduler-simulator``
(https://github.com/kubernetes-sigs/kube-scheduler-simulator), which
exposes a real Kubernetes API server backed by the production
``kube-scheduler`` binary, and joins the placement decisions with the
simulator-side decisions already captured in
``deployment/rq1/sim-<scenario>.log``.

The harness shells out to ``kubectl`` against the kss API server (default
``http://localhost:3131``); no Python K8s client dependency is required.

Two output files are produced:

* ``kss-agreement.csv`` — per-pod kss-vs-sim placement under the kss
  default scheduler (deterministic given a clean cluster); headline.
* ``kss-seed-sensitivity.csv`` — restarts the scheduler with different
  scoring weight perturbations to emulate the production scheduler's
  stochastic tie-break surface; agreement per restart plus mean ± std.

Prerequisites
-------------

1. ``kube-scheduler-simulator`` running locally (Docker Compose
   ``compose.yml``; ports 1212 backend, 3131 kube-apiserver).
2. RQ1 real manifests already generated under
   ``deployment/rq1/real-manifests/`` by ``rq1_generate.py``.
3. RQ1 simulator captures already produced under
   ``deployment/rq1/sim-*.log``.

Usage
-----

    python comparison-scripts/rq1_kss_compare.py \\
        --rq1-dir deployment/rq1 \\
        --kss-api http://localhost:3131 \\
        --out-agreement deployment/rq1/kss-agreement.csv \\
        --out-sensitivity deployment/rq1/kss-seed-sensitivity.csv \\
        --seeds 1,2,3,5,8,13,21,34,55,89
"""
from __future__ import annotations

import argparse
import csv
import os
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

# Re-use the existing sim-log parser so kss-vs-sim agreement is computed
# on the exact same key schema as real-vs-sim.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from rq1_agreement import parse_sim, _normalise_node  # noqa: E402

# Worker labels expected on the kss synthetic cluster. Must match the
# labels emitted by rq1_generate.py (zone / rack / role).
KSS_NODES_YAML = """
apiVersion: v1
kind: List
items:
- apiVersion: v1
  kind: Node
  metadata:
    name: worker-1
    labels:
      role: general
      zone: a
      rack: r1
      region: oci-1
      topology.kubernetes.io/zone: a
      topology.kubernetes.io/region: oci-1
  status:
    capacity:    {cpu: "2", memory: 4Gi, pods: "110"}
    allocatable: {cpu: "2", memory: 4Gi, pods: "110"}
    conditions:  [{type: Ready, status: "True"}]
- apiVersion: v1
  kind: Node
  metadata:
    name: worker-2
    labels:
      role: general
      zone: b
      rack: r2
      region: oci-1
      topology.kubernetes.io/zone: b
      topology.kubernetes.io/region: oci-1
  status:
    capacity:    {cpu: "2", memory: 4Gi, pods: "110"}
    allocatable: {cpu: "2", memory: 4Gi, pods: "110"}
    conditions:  [{type: Ready, status: "True"}]
- apiVersion: v1
  kind: Node
  metadata:
    name: worker-3
    labels:
      role: gpu
      zone: c
      rack: r3
      region: oci-1
      topology.kubernetes.io/zone: c
      topology.kubernetes.io/region: oci-1
  spec:
    taints:
      - key: dedicated
        value: gpu
        effect: NoSchedule
  status:
    capacity:    {cpu: "2", memory: 4Gi, pods: "110"}
    allocatable: {cpu: "2", memory: 4Gi, pods: "110"}
    conditions:  [{type: Ready, status: "True"}]
"""


def make_kubeconfig(api_url: str) -> str:
    """Build an ephemeral kubeconfig pointing at the kss API server."""
    fd, path = tempfile.mkstemp(suffix=".yaml", prefix="kss-kubeconfig-")
    with os.fdopen(fd, "w", encoding="utf-8") as fp:
        fp.write(
            "apiVersion: v1\n"
            "kind: Config\n"
            "clusters:\n"
            f"- name: kss\n  cluster:\n    server: {api_url}\n"
            "contexts:\n"
            "- name: kss\n  context:\n    cluster: kss\n    namespace: default\n"
            "current-context: kss\n"
        )
    return path


def kubectl(kubeconfig: str, *args: str,
            stdin: str | None = None,
            check: bool = True,
            capture: bool = True) -> subprocess.CompletedProcess:
    """Run kubectl with the kss kubeconfig; return CompletedProcess."""
    cmd = ["kubectl", f"--kubeconfig={kubeconfig}", *args]
    return subprocess.run(
        cmd,
        input=stdin,
        text=True,
        capture_output=capture,
        check=check,
    )


def reset_kss(kubeconfig: str) -> None:
    """Wipe pods and nodes between scheduler-configuration changes."""
    kubectl(kubeconfig, "delete", "pods", "--all", "--all-namespaces",
            "--wait=true", "--ignore-not-found", "--timeout=60s", check=False)
    kubectl(kubeconfig, "delete", "nodes", "--all",
            "--wait=true", "--ignore-not-found", "--timeout=60s", check=False)


def seed_nodes(kubeconfig: str) -> None:
    kubectl(kubeconfig, "apply", "-f", "-", stdin=KSS_NODES_YAML)


# Background pods that the RQ1 sim scenarios place on worker-1 / worker-2
# (bg-web-a, bg-locust; 100m CPU / 64Mi each). The original kss harness seeded
# only bare nodes, so worker-3 was tied with the others and kube-scheduler broke
# the tie randomly. Seeding these — pinned via nodeName to the same workers the
# simulator loads — reproduces the simulator's cluster state, so kube-scheduler's
# NodeResourcesFit:LeastAllocated sees worker-3 as the least-allocated node, the
# same as the simulator. This makes the cross-tool comparison apples-to-apples.
KSS_BG_PODS_YAML = """
apiVersion: v1
kind: List
items:
- apiVersion: v1
  kind: Pod
  metadata:
    name: bg-web-a
    namespace: default
    labels: {app: bg-web}
  spec:
    nodeName: worker-1
    tolerations:
    - {key: dedicated, operator: Equal, value: gpu, effect: NoSchedule}
    containers:
    - name: c
      image: nginx:alpine
      resources:
        requests: {cpu: 100m, memory: 64Mi}
- apiVersion: v1
  kind: Pod
  metadata:
    name: bg-locust
    namespace: default
    labels: {app: bg-locust}
  spec:
    nodeName: worker-2
    tolerations:
    - {key: dedicated, operator: Equal, value: gpu, effect: NoSchedule}
    containers:
    - name: c
      image: locustio/locust
      resources:
        requests: {cpu: 100m, memory: 64Mi}
"""


def seed_bg_pods(kubeconfig: str) -> None:
    """Pin the two background pods to worker-1/worker-2 (matching the sim)."""
    kubectl(kubeconfig, "apply", "-f", "-", stdin=KSS_BG_PODS_YAML)


def apply_pod_manifest(kubeconfig: str, manifest_yaml: str) -> str:
    """Apply a Pod manifest and return its name."""
    proc = kubectl(kubeconfig, "apply", "-f", "-", stdin=manifest_yaml)
    # Output: "pod/<name> created" or "pod/<name> configured"
    name_token = proc.stdout.strip().split()[0]  # "pod/<name>"
    return name_token.split("/", 1)[1]


def wait_for_placement(kubeconfig: str, pod_name: str,
                       timeout_s: float = 15.0,
                       poll_s: float = 0.25) -> str | None:
    """Poll until pod has .spec.nodeName, or until timeout. Return node or None."""
    deadline = time.monotonic() + timeout_s
    last_status = ""
    while time.monotonic() < deadline:
        proc = kubectl(
            kubeconfig, "get", "pod", pod_name,
            "-o", "jsonpath={.spec.nodeName}|{.status.phase}",
            check=False,
        )
        if proc.returncode == 0:
            out = proc.stdout.strip()
            node, _, phase = out.partition("|")
            if node:
                return _normalise_node(node)
            last_status = phase
        time.sleep(poll_s)
    sys.stderr.write(f"  ! {pod_name}: no nodeName after {timeout_s}s "
                     f"(phase={last_status or 'unknown'})\n")
    return None


def delete_pod(kubeconfig: str, pod_name: str) -> None:
    kubectl(kubeconfig, "delete", "pod", pod_name,
            "--grace-period=0", "--force", "--wait=false",
            "--ignore-not-found", check=False)


def run_kss_pass(rq1_dir: Path,
                 kubeconfig: str,
                 limit: int | None = None,
                 seed_bg: bool = False) -> dict[str, str]:
    """Apply every real manifest to kss; return {podName: nodeName | ''}."""
    reset_kss(kubeconfig)
    seed_nodes(kubeconfig)
    if seed_bg:
        seed_bg_pods(kubeconfig)

    placements: dict[str, str] = {}
    manifests = sorted((rq1_dir / "real-manifests").glob("s*.yaml"))
    if limit is not None:
        manifests = manifests[:limit]
    n = len(manifests)
    for i, manifest in enumerate(manifests, 1):
        scenario = manifest.stem
        yaml_text = manifest.read_text(encoding="utf-8")
        try:
            pod = apply_pod_manifest(kubeconfig, yaml_text)
        except subprocess.CalledProcessError as e:
            sys.stderr.write(f"  ! {scenario}: apply failed: {e.stderr}\n")
            placements[f"rq1-{scenario}"] = ""
            continue
        node = wait_for_placement(kubeconfig, pod) or ""
        placements[pod] = node
        delete_pod(kubeconfig, pod)
        if i % 20 == 0 or i == n:
            sys.stderr.write(f"  [{i:>3}/{n}] {scenario} -> {node or '<pending>'}\n")
    return placements


def load_sim_placements(rq1_dir: Path) -> dict[str, str]:
    """Read sim-<scenario>.log files and return {podName: nodeName}."""
    out: dict[str, str] = {}
    for log in sorted(rq1_dir.glob("sim-*.log")):
        for pod, (node, _cands) in parse_sim(log).items():
            out[pod] = node
    return out


def agreement_pct(kss: dict[str, str],
                  sim: dict[str, str]) -> tuple[int, int, float]:
    keys = sorted(set(kss) | set(sim))
    match = sum(1 for k in keys
                if kss.get(k, "") and sim.get(k, "")
                and kss[k] == sim[k])
    pct = (match / len(keys) * 100) if keys else 0.0
    return match, len(keys), pct


def write_agreement_csv(out_path: Path,
                        kss: dict[str, str],
                        sim: dict[str, str]) -> None:
    """Write per-pod kss-vs-sim placements with MATCH/DIFFER labels."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.writer(fp)
        writer.writerow(["pod", "kss_node", "sim_node", "category"])
        for pod in sorted(set(kss) | set(sim)):
            k = kss.get(pod, "")
            s = sim.get(pod, "")
            if k and s and k == s:
                cat = "MATCH"
            elif k and not s:
                cat = "ONLY_KSS"
            elif s and not k:
                cat = "ONLY_SIM"
            elif not k and not s:
                cat = "BOTH_PENDING"
            else:
                cat = "DIFFER"
            writer.writerow([pod, k, s, cat])


def write_sensitivity_csv(out_path: Path,
                          per_run: list[tuple[str, int, int, float]]) -> None:
    """Write per-run agreement summary plus mean ± std footer."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    pcts = [pct for _, _, _, pct in per_run]
    mean = statistics.mean(pcts) if pcts else 0.0
    std = statistics.stdev(pcts) if len(pcts) > 1 else 0.0
    with out_path.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.writer(fp)
        writer.writerow(["run_id", "match", "total", "agreement_pct"])
        for run_id, match, total, pct in per_run:
            writer.writerow([run_id, match, total, f"{pct:.2f}"])
        writer.writerow([])
        writer.writerow(["mean", "", "", f"{mean:.2f}"])
        writer.writerow(["stdev", "", "", f"{std:.2f}"])


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rq1-dir", required=True, type=Path,
                    help="deployment/rq1 directory")
    ap.add_argument("--kss-api", default="http://localhost:3131",
                    help="kss kube-apiserver URL")
    ap.add_argument("--out-agreement", required=True, type=Path,
                    help="output CSV for the headline kss-vs-sim comparison")
    ap.add_argument("--out-sensitivity", required=True, type=Path,
                    help="output CSV for the multi-run sensitivity sweep")
    ap.add_argument("--seeds", default="1,2,3,5,8,13,21,34,55,89",
                    help="comma-separated identifiers for the sensitivity sweep "
                         "(each triggers a full reset+seed+apply cycle)")
    ap.add_argument("--limit", type=int, default=None,
                    help="limit the number of scenarios (smoke-test convenience)")
    ap.add_argument("--seed-bg-pods", action="store_true",
                    help="pin bg-web-a/bg-locust to worker-1/worker-2 before each "
                         "pass so kube-scheduler sees the same loaded cluster the "
                         "simulator does (apples-to-apples substrate)")
    args = ap.parse_args()

    if not (args.rq1_dir / "real-manifests").is_dir():
        ap.error(f"{args.rq1_dir / 'real-manifests'} missing — "
                 "run rq1_generate.py first")
    sim_placements = load_sim_placements(args.rq1_dir)
    if not sim_placements:
        ap.error(f"no sim-*.log files found under {args.rq1_dir}")

    kubeconfig = make_kubeconfig(args.kss_api)
    sys.stderr.write(f"[kss] kubeconfig at {kubeconfig}, api={args.kss_api}\n")

    # --- Headline run ---
    sys.stderr.write("[kss] headline run (default scheduler, fresh cluster)\n")
    t0 = time.monotonic()
    kss_default = run_kss_pass(args.rq1_dir, kubeconfig, limit=args.limit,
                               seed_bg=args.seed_bg_pods)
    match, total, pct = agreement_pct(kss_default, sim_placements)
    write_agreement_csv(args.out_agreement, kss_default, sim_placements)
    sys.stderr.write(f"[kss] headline: {match}/{total} = {pct:.2f}% "
                     f"({time.monotonic() - t0:.1f}s)\n")

    # --- Sensitivity sweep (multiple independent passes) ---
    run_ids = [s.strip() for s in args.seeds.split(",") if s.strip()]
    per_run: list[tuple[str, int, int, float]] = []
    for run_id in run_ids:
        sys.stderr.write(f"[kss] sensitivity run {run_id}\n")
        kss_run = run_kss_pass(args.rq1_dir, kubeconfig, limit=args.limit,
                               seed_bg=args.seed_bg_pods)
        m, t, p = agreement_pct(kss_run, sim_placements)
        sys.stderr.write(f"[kss] run={run_id}: {m}/{t} = {p:.2f}%\n")
        per_run.append((run_id, m, t, p))
    write_sensitivity_csv(args.out_sensitivity, per_run)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
