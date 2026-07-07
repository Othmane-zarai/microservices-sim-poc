#!/usr/bin/env python3
"""
Compare real-cluster vs simulator metrics for the Online Boutique
validation track. Produces `comparison-report.md`.

Required inputs (current directory):
    real-placement.csv          from collect-metrics.sh
    real-hpa.csv                from collect-metrics.sh
    real-latency.csv            from collect-metrics.sh
    sim-placement.csv           emitted by K8sOnlineBoutiqueExample with -Dk8s.emitPlacementCsv=true
    sim-hpa.csv                 emitted by K8sOnlineBoutiqueExample
    sim-latency.csv             emitted by K8sOnlineBoutiqueExample with -Dk8s.emitLatencyCsv=true

Outputs `comparison-report.md` plus a couple of plots
(`placement-comparison.png`, `hpa-trajectory.png`).
"""
from __future__ import annotations

import argparse
import math
import sys
from pathlib import Path

try:
    import pandas as pd
except ImportError:
    print("pandas required: pip install pandas matplotlib", file=sys.stderr)
    sys.exit(1)


def _strip_node(name: str) -> str:
    """Normalise k3s/k8s node names: `k3s-worker-1` and `worker-1` both -> `worker-1`."""
    return name.removeprefix('k3s-').removeprefix('node-')


def winner_agreement(real: pd.DataFrame, sim: pd.DataFrame) -> tuple[int, int]:
    """Return (matches, total) counting pod-to-node agreements by service."""
    # Group both sides by service (everything before the deployment hash).
    real = real.copy()
    sim = sim.copy()
    real["service"] = real["pod"].str.replace(r"-[a-z0-9]+-[a-z0-9]+$", "", regex=True)
    sim["service"] = sim["pod"].str.replace(r"-\d+$", "", regex=True)
    # Normalise node names so k3s control-plane / worker prefixes don't break matching.
    real["node"] = real["node"].astype(str).map(_strip_node)
    sim["node"]  = sim["node"].astype(str).map(_strip_node)
    matches = 0
    total = 0
    by_svc = {}
    for svc in sorted(set(real["service"]) | set(sim["service"])):
        r_nodes = sorted(real[real["service"] == svc]["node"].tolist())
        s_nodes = sorted(sim[sim["service"] == svc]["node"].tolist())
        total += max(len(r_nodes), len(s_nodes))
        svc_match = 0
        for r, s in zip(r_nodes, s_nodes):
            if r == s:
                svc_match += 1
        by_svc[svc] = (svc_match, max(len(r_nodes), len(s_nodes)),
                       r_nodes, s_nodes)
        matches += svc_match
    # Stash per-service detail for the report
    winner_agreement.by_svc = by_svc
    return matches, total


def hpa_nrmsd(real: pd.DataFrame, sim: pd.DataFrame, service: str) -> float:
    """NRMSD of desired replica counts over time for a given service.

    Normalisation uses max(r ∪ s) − min(r ∪ s) so that a sim series that
    stays at 1 while the real scales 1→3 gives NRMSD ≤ 1.0 rather than
    blowing up when the real-only range is 1.
    """
    r = real[real["service"] == service].sort_values("timestamp")["desired"].astype(float)
    # Drop HPA initialisation rows where desired=0 (happens in the first
    # few seconds after kubectl apply when currentReplicas is not yet synced).
    r = r[r > 0]
    s = sim[sim["service"] == service].sort_values("timestamp")["desired"].astype(float)
    s = s[s > 0]
    if r.empty or s.empty:
        return float("nan")
    n = min(len(r), len(s))
    r = r.iloc[:n].reset_index(drop=True)
    s = s.iloc[:n].reset_index(drop=True)
    combined_rng = max(r.max(), s.max()) - min(r.min(), s.min())
    if combined_rng < 1e-9:
        return 0.0
    err = ((r - s) ** 2).mean() ** 0.5
    return float(err / combined_rng)


def latency_ratios(real: pd.DataFrame, sim: pd.DataFrame) -> pd.DataFrame:
    """Per-endpoint sim/real ratio at p50, p95, p99.

    Sim paths use service names (e.g. /frontend) while real paths use HTTP
    routes (e.g. GET /). Map the three primary services that loadgenerator
    actually exercises to their dominant real-side counterparts.
    """
    # Map sim service-path → real URL path (dominant entry point)
    SIM_TO_REAL = {
        ("GET", "/frontend"):         ("GET",  "/"),
        ("GET", "/checkoutservice"):  ("POST", "/cart/checkout"),
        ("GET", "/recommendationservice"): ("GET", "/"),
        ("GET", "/productcatalogservice"): ("GET", "/product/0PUK6V6EV0"),
        ("GET", "/cartservice"):      ("GET",  "/cart"),
    }

    r = real.set_index(["method", "path"])
    s = sim.set_index(["method", "path"])
    rows = []
    for sim_key, real_key in SIM_TO_REAL.items():
        if sim_key not in s.index or real_key not in r.index:
            continue
        rr = r.loc[real_key]
        ss = s.loc[sim_key]
        rows.append({
            "sim_endpoint": f"{sim_key[0]} {sim_key[1]}",
            "real_endpoint": f"{real_key[0]} {real_key[1]}",
            "p50_real": rr.p50_ms, "p50_sim": float(ss.p50_ms),
            "p95_real": rr.p95_ms, "p95_sim": float(ss.p95_ms),
            "p95_ratio": float(ss.p95_ms) / max(float(rr.p95_ms), 1e-6),
        })
    return pd.DataFrame(rows)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=".", help="directory with the CSV inputs")
    args = ap.parse_args()
    d = Path(args.dir)

    required = [
        "real-placement.csv", "real-hpa.csv", "real-latency.csv",
        "sim-placement.csv", "sim-hpa.csv", "sim-latency.csv",
    ]
    missing = [f for f in required if not (d / f).exists()]
    if missing:
        print(f"ERROR: missing input files: {missing}", file=sys.stderr)
        print("Run collect-metrics.sh (real cluster) AND the simulator example "
              "with -Dk8s.emitPlacementCsv=true -Dk8s.emitLatencyCsv=true first.",
              file=sys.stderr)
        sys.exit(1)

    rp = pd.read_csv(d / "real-placement.csv")
    sp = pd.read_csv(d / "sim-placement.csv")
    rh = pd.read_csv(d / "real-hpa.csv")
    sh = pd.read_csv(d / "sim-hpa.csv")
    rl = pd.read_csv(d / "real-latency.csv")
    sl = pd.read_csv(d / "sim-latency.csv")

    matches, total = winner_agreement(rp, sp)
    agreement = matches / total if total else 0.0

    elastic = ["frontend", "checkoutservice", "recommendationservice"]
    nrmsds = {svc: hpa_nrmsd(rh, sh, svc) for svc in elastic}

    latency = latency_ratios(rl, sl)

    report = d / "comparison-report.md"
    with report.open("w", encoding="utf-8") as out:
        out.write("# Online Boutique — Real vs Simulator Comparison\n\n")
        out.write(f"## Placement agreement\n\n")
        out.write(f"- Winner agreement: **{matches}/{total} = {agreement:.1%}**\n")
        out.write(f"- Target: >= 90%\n\n")
        out.write("## HPA trajectory NRMSD\n\n")
        out.write("| Service | NRMSD |\n|---|---|\n")
        for svc, v in nrmsds.items():
            tag = " ✓" if (not math.isnan(v) and v < 0.20) else " ✗"
            out.write(f"| {svc} | {v:.3f}{tag} |\n")
        out.write("\nTarget: NRMSD < 0.20 per service.\n\n")
        out.write("## Latency sim/real ratios (sim M/M/c vs Locust real)\n\n")
        if not latency.empty:
            out.write(latency.to_markdown(index=False, floatfmt=".3f"))
            out.write("\n\nTarget: p95 ratio < 0.5 (sim is a lower bound by design).\n")
        else:
            out.write("_No matching endpoints — sim uses service names (/frontend), "
                      "real uses URL paths (/). Trace comparison covers per-operation "
                      "latency; see trace-comparison.md._\n")
    print(f"Wrote {report}")


if __name__ == "__main__":
    main()
