# Deployment kit — Kubernetes scenarios + empirical validation

This folder is the operator's home for the project's Kubernetes simulation
work. It pairs a curated set of YAML cluster topologies (mounted on the
classpath under `src/main/resources/k8s-clusters/scenarios/`) with a runner
script and an empirical-validation playbook (`EMPIRICAL_VALIDATION.md`)
that compares the simulator's output against a real k3s cluster.

| File | Purpose |
|---|---|
| `run-scenario.ps1` / `run-scenario.sh` | Build the fat jar (once) and run a named scenario via `K8sClusterFromYamlExample`. |
| `EMPIRICAL_VALIDATION.md` | Step-by-step guide: deploy a workload on your real cluster, drive load with Locust, capture metrics, run the matching simulator scenario, compare. |
| `rq1/`, `rq2/`, `rq3/`, `rq-latency/` | (Created on demand) Result directories for the four research-question captures. |

The scenarios themselves are bundled into the fat jar — they live at
[`src/main/resources/k8s-clusters/scenarios/`](../src/main/resources/k8s-clusters/scenarios/)
and are loaded by `K8sClusterFromYamlExample` via the classpath.

## Quick start

Run the simplest scenario end-to-end (default duration is 120 simulated
seconds):

```powershell
# From the project root
.\deployment\run-scenario.ps1 -Scenario 01-baseline
```

```bash
./deployment/run-scenario.sh 01-baseline
```

First invocation builds `target/csp-examples-springboot-1.0.0-SNAPSHOT.jar`
(~30 s); subsequent runs reuse the cached jar. Each run prints a structured
summary: cluster name, total nodes, placed/unschedulable pod counts,
per-deployment HPA desired-replica counts, the pod-to-node placement
table, and Cluster Autoscaler scale events.

List the available scenarios:

```powershell
.\deployment\run-scenario.ps1
```

Run them all back-to-back:

```powershell
.\deployment\run-scenario.ps1 -All -Duration 180
```

## Scenarios

The "Runnable on 4-VM" column reflects empirical-validation feasibility on
the reference hardware (1 control plane + 3 workers k3s). Scenarios marked
with a TODO ID still run in the simulator end-to-end; they just cannot be
cross-checked against a real cluster of the required size — see
[`EMPIRICAL_VALIDATION.md` §9](EMPIRICAL_VALIDATION.md#9-todo-inventory)
for the unlock conditions.

| File | Goal | Features exercised | Runnable on 4-VM |
|---|---|---|---|
| [`01-baseline.yaml`](../src/main/resources/k8s-clusters/scenarios/01-baseline.yaml) | Smallest sanity check: 3 cheap nodes, 3 nginx replicas | scheduler, `COST_OPTIMIZED` policy, basic placement | ✅ |
| [`02-cost-optimized.yaml`](../src/main/resources/k8s-clusters/scenarios/02-cost-optimized.yaml) | Heterogeneous cost — verifies cheapest-fit placement | `COST_OPTIMIZED`, mixed instance types | ✅ (heterogeneity emulated via labels) |
| [`03-rack-anti-affinity.yaml`](../src/main/resources/k8s-clusters/scenarios/03-rack-anti-affinity.yaml) | Spread replicas across racks to survive a rack failure | `RACK_ANTI_AFFINITY` policy | ❌ `TODO-RACK` (needs ≥6 workers) |
| [`04-zone-spread.yaml`](../src/main/resources/k8s-clusters/scenarios/04-zone-spread.yaml) | Spread replicas across availability zones | `AVAILABILITY_ZONE_SPREAD` policy | ❌ `TODO-ZONE` (needs ≥6 workers across 3 ADs) |
| [`05-multi-region.yaml`](../src/main/resources/k8s-clusters/scenarios/05-multi-region.yaml) | Geographic spread across regions for disaster recovery | `GEOGRAPHIC_SPREAD` policy, multi-region nodes | ❌ `TODO-MULTIREGION` (needs multi-region cluster) |
| [`06-autoscaling-stress.yaml`](../src/main/resources/k8s-clusters/scenarios/06-autoscaling-stress.yaml) | Saturate the cluster, force HPA + ClusterAutoscaler scale-up | HPA, ClusterAutoscaler, NodePool, dynamic load | ⚠️ HPA half ✅; CA half is `TODO-CA-REAL` (k3s has no built-in CA) |
| [`07-mixed-workloads.yaml`](../src/main/resources/k8s-clusters/scenarios/07-mixed-workloads.yaml) | Web tier + ML training tier with NodeAffinity, taints, tolerations | NodeAffinity, taints / tolerations, dual Deployments, mixed namespaces | ✅ (reduce to 2 general + 1 GPU worker) |

The top-level cluster YAMLs (`cluster-10nodes.yaml`,
`cluster-rack-anti-affinity.yaml`, `cluster-zone-spread.yaml`,
`cluster-multi-region.yaml`, `cluster-latency-aware.yaml`,
`saturation-experiment.yaml`) are all simulator-only on 4-VM hardware
(`TODO-RACK`, `TODO-ZONE`, `TODO-MULTIREGION`, `TODO-LATENCY-AWARE`,
`TODO-SATURATION`, `TODO-SCALABILITY-100`).

## Empirical validation

If you have a real Kubernetes cluster reachable via `kubectl`, follow
[`EMPIRICAL_VALIDATION.md`](EMPIRICAL_VALIDATION.md) to capture ground
truth and compare it to the simulator's output. The guide assumes the
cluster is already provisioned and reachable — it walks you through the
workload deploy, the Locust load drive, the metric captures (placement,
HPA trajectory, latency, autoscaler events), the matching simulator run,
and the side-by-side comparison.

## Schema reference (consumed by `K8sClusterFromYamlExample`)

```yaml
cluster:
  name: <string>
  scheduler:
    policy: COST_OPTIMIZED | LATENCY_AWARE | AVAILABILITY_ZONE_SPREAD
          | RACK_ANTI_AFFINITY | GEOGRAPHIC_SPREAD
    k8sScoreScale: <double>     # default 0.01; bigger ⇒ K8s prefs dominate cost / spread
  controllerTickIntervalSeconds: <double>     # default 1.0

  nodes:
    - name: <string>
      pes: <int>                # number of CPU cores
      mipsPerCore: <int>        # default 1000
      ramMiB: <long>
      bwMbps: <long>            # optional; informational
      rack: <string>
      zone: <string>
      region: <string>
      costPerHour: <double>
      schedulable: <bool>       # default true; false ⇔ kubectl cordon
      labels: { <key>: <value>, … }
      taints:
        - { key: <string>, value: <string>, effect: NO_SCHEDULE | NO_EXECUTE | PREFER_NO_SCHEDULE }

  workload:
    deployments:
      - name: <string>
        namespace: <string>     # default
        replicas: <int>
        labels: { <key>: <value>, … }
        cpuLoadProfile: HIGH_90 | STEADY_50 | BURSTY | IDLE_15
        nodeAffinity:
          required:
            - { key: <string>, operator: In | NotIn | Exists | DoesNotExist, values: [<string>, …] }
          preferred:
            - { weight: <int>, key: <string>, operator: …, values: […] }
        tolerations:
          - { key: <string>, operator: Equal | Exists, value: <string>, effect: … }
        container:
          image: <string>
          cpu: <string>          # K8s syntax: "500m", "2"
          memory: <string>       # K8s syntax: "256Mi", "8Gi"
          length: <long>         # cloudlet length in MI (Million Instructions)

    autoscalers:
      - kind: HPA
        target: <deployment-name>
        minReplicas: <int>
        maxReplicas: <int>
        cpuTargetUtilization: <double>      # in [0, 1]
        cooldownSeconds: <double>

      - kind: ClusterAutoscaler
        poolName: <string>
        minNodes: <int>
        maxNodes: <int>
        scaleDownAfterSeconds: <double>
        cooldownSeconds: <double>
        nodeTemplate: { <NodeSpec without name> }
```

## Reproducibility

Every scenario produces deterministic placement: the
`KubernetesScheduler` E5 lexical tie-break ensures that two runs with the
same YAML on the same JVM (or different JVMs) emit byte-identical
placement tables. Re-run any scenario and diff the output to confirm.
