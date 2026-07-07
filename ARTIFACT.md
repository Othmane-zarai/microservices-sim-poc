# ARTIFACT.md — CloudSim Plus-K8s

Measured manifest of the artifact behind the manuscript. All figures were
counted on 2026-05-29 from the working trees described below. Update this file
whenever the counts change.

## Repositories

| Component | Location | Public? |
|---|---|---|
| Kubernetes extension source (`org.cloudsimplus.kubernetes`, topology substrate) | CloudSim Plus fork `Othmane-zarai/cloudsimplus-k8s` (formerly `…/cloudsimplus`); local checkout `…/cloudsimplus`. Clean snapshot committed on `master` (`80843ea`) with the former WIP folded in | Public (fork); tagged **`v1.0.1`** (canonical) and `v1.0.0-k8s` |
| Worked examples, deployment kit, comparator scripts, raw captures | `microservices-sim-poc` (remote `Othmane-zarai/microservices-sim-poc`, GPL-3.0-or-later for code / CC-BY-4.0 for data+paper; branches `dev`/`master`) | **Verify public before submission** — Zenodo GitHub integration needs a public repo |

> **Reproducibility caveat.** The extension as described in the paper was
> `6f09bc8` **plus ~36 then-uncommitted local changes** (notably the
> `networking/queueing` M/M/c sub-package). Those changes are now committed and
> tagged: archive **`v1.0.1`** (or `v1.0.0-k8s`) to Zenodo. Note the 8 failing
> tests below are still open — get the suite green before minting the DOI that
> goes in the camera-ready.

## Measured counts (Kubernetes extension, `org/cloudsimplus/kubernetes`)

| Quantity | Value | How measured |
|---|---|---|
| Source files (`.java`) | **63** (0 `package-info`) | `find … -name '*.java' \| wc -l` |
| Top-level public types | **63** | one public type per file |
| — classes | **48** | `grep '^public …class '` |
| — interfaces | **5** | `grep '^public …interface '` |
| — records | **5** | `grep '^public record '` |
| — enums | **5** | `grep '^public enum '` |
| Sub-packages | **10** | autoscaling, builders, controllers, lifecycle, networking, networking/queueing, scheduler, security, storage, tracing |
| Topology substrate (outside the 63, in core packages) | **2** | `TopologyAwareHost`, `VmAllocationPolicyTopologyAware` (both authored in `6f09bc8`) |

## Worked examples (`org.cloudsimplus.examples.kubernetes`, wrapper repo)

**15** runnable `K8s*Example` classes (each with a `main`):
`K8sAffinity`, `K8sClusterAutoscaler`, `K8sCluster`, `K8sClusterFromYaml`,
`K8sCronJob`, `K8sCustomScheduler`, `K8sDaemonSet`, `K8sHPA`, `K8sJob`,
`K8sOnlineBoutique`, `K8sPlanetLab`, `K8sProbes`, `K8sReplicaSet`,
`K8sStatefulSet`, `K8sVPA`.

## Tests

| Suite | Files | `@Test` methods | Current result |
|---|---|---|---|
| Contract / regression (fork, `…/kubernetes`) | 33 | **169** | **169 run, 5 FAIL** (4 `ResourcesParsingModeTest` lenient-warn; 1 `HpaTrajectoryShapeTest.replicaTrajectoryMatchesRealClusterShape`) — all in WIP files |
| Wrapper (`microservices-sim-poc/src/test`) | 16 | 26 (25 default + 1 `@Tag("benchmark")`) | **25 run, 3 FAIL** (2 `K8sCustomSchedulerExampleTest` bin-packing; 1 `K8sJobExampleTest`) |

> The 8 current failures are in newly added / modified work-in-progress files.
> A clean snapshot must pass the full suite before the "test-enforced contract"
> claim holds end-to-end.

## Build & test commands

Requires **JDK 25** (measured: `25.0.1`).

```bash
# Kubernetes extension (CloudSim Plus fork) — build + contract tests
cd cloudsimplus
./mvnw.cmd clean install            # builds + installs 9.0.0-SNAPSHOT to ~/.m2
./mvnw.cmd test -Dtest='org.cloudsimplus.kubernetes.**'

# Worked examples + REST/CLI wrapper (microservices-sim-poc)
cd microservices-sim-poc
mvn clean package                   # excludes @Tag("benchmark") by default
mvn test -Dgroups=benchmark         # run the scheduler benchmark explicitly
```

## Toolchain (pinned for the empirical evaluation)

| Tool | Version | Source |
|---|---|---|
| JDK | 25.0.1 | sim run logs |
| CloudSim Plus | 9.0.0-SNAPSHOT (fork) | `pom.xml` |
| Google Online Boutique (`microservices-demo`) | `v0.10.1` (10 app services + load generator; cartservice→Redis) | `deployment/online-boutique/deploy.sh` |
| Jaeger | `all-in-one:1.57` | `tracing-stack.yaml` |
| OpenTelemetry Collector | `opentelemetry-collector-contrib:0.103.0` | `tracing-stack.yaml` |
| k3s | `v1.35.4+k3s1` (not captured in artifact; `containerd 2.2.3-k3s1` recorded) | `REPRODUCIBILITY.md` |
| kube-scheduler-simulator | `v0.4.0` (Docker Compose; KWOK fake cluster) | `deployment/rq1/KSS_SUMMARY.md` |
