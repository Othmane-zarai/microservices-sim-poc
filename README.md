# CloudSim Plus-K8s — Examples & Evaluation

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.21251746.svg)](https://doi.org/10.5281/zenodo.21251746)

Companion artifact to the paper **"CloudSim Plus-K8s: A Contract-Driven
Extension for Kubernetes Container Orchestration Simulation"**
(`paper/kubernetes-cloudsimplus.tex`).

It bundles:

- **Runnable Kubernetes examples** (`org.cloudsimplus.examples.kubernetes`) that
  exercise the upstream `org.cloudsimplus.kubernetes` orchestration runtime —
  pod scheduling (filter/score), HPA, VPA, cluster autoscaler, workload
  controllers (ReplicaSet/StatefulSet/DaemonSet/Job/CronJob), probes, affinity,
  and M/M/c per-service latency.
- **A headless CLI runner** (`com.example.cspsim`) that discovers and executes
  those examples.
- **The empirical evaluation** — the RQ1–RQ4 + Google Online Boutique deployment
  kit and raw real-vs-sim captures (`deployment/`), the analysis/comparator
  scripts (`comparison-scripts/`), and the LaTeX paper sources (`paper/`). These
  back the paper's Data Availability Statement and are what gets archived to
  Zenodo; see `ZENODO.md` (deposit guide) and `ARTIFACT.md` (measured manifest).

> This project began as a Spring Boot wrapper exposing *all* upstream
> `cloudsimplus-examples` over REST. It has been scoped down to only the
> Kubernetes work that backs the paper; the REST API, Thymeleaf web UI, and
> Elasticsearch metrics export have been removed.

---

## 1. Requirements

| Tool          | Version          |
|---------------|------------------|
| JDK           | **25** (sources use the Java 25 instance-`main` syntax) |
| Maven         | 3.9+ (or the bundled `mvnw` wrapper) |
| Spring Boot   | 3.5.0 (managed by the parent POM) |
| CloudSim Plus | 9.0.0-SNAPSHOT (WIP fork; managed dependency) |

Set `JAVA_HOME` to a Java 25 install before building.

---

## 2. Build

```bash
mvn clean package -DskipTests
```

Produces an executable fat jar at:

```
target/cloudsimplus-k8s-examples-1.0.0-SNAPSHOT.jar
```

---

## 3. Running

The application is **headless** (`spring.main.web-application-type=none`): the
CLI runner executes the requested example and the JVM exits. No web server is
started.

### 3.1 List every discovered example

```bash
java -jar target/cloudsimplus-k8s-examples-1.0.0-SNAPSHOT.jar --list
```

```
Available CloudSim Plus examples:
  - K8sAffinityExample
  - K8sClusterAutoscalerExample
  - K8sClusterExample
  - K8sClusterFromYamlExample
  - K8sCronJobExample
  - K8sCustomSchedulerExample
  - K8sDaemonSetExample
  - K8sHPAExample
  - K8sJobExample
  - K8sOnlineBoutiqueExample
  - K8sPlanetLabExample
  - K8sProbesExample
  - K8sReplicaSetExample
  - K8sStatefulSetExample
  - K8sVPAExample
```

### 3.2 Run a single example

Pass the example's **simple class name** (or its fully qualified name):

```bash
# by simple name
java -jar target/cloudsimplus-k8s-examples-1.0.0-SNAPSHOT.jar --example=K8sHPAExample

# by fully qualified name
java -jar target/cloudsimplus-k8s-examples-1.0.0-SNAPSHOT.jar \
     --example=org.cloudsimplus.examples.kubernetes.K8sClusterExample
```

You will see the CloudSim Plus log followed by a final line:

```
INFO  Example K8sHPAExample finished in 412 ms (success=true)
```

Some examples take parameters via system properties, e.g.
`K8sClusterFromYamlExample` reads `-Dk8syaml.config=k8s-clusters/cluster-10nodes.yaml`.

### 3.3 Maven `spring-boot:run`

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--example=K8sClusterExample
```

---

## 4. Testing

```bash
mvn test                       # JUnit suite under src/test/.../kubernetes/
mvn test -Dgroups=benchmark    # the @Tag("benchmark") performance sweep (excluded by default)
```

Resources read by examples (`workload/planetlab/`, `k8s-clusters/`) are bundled
inside the fat jar — nothing extra to set up.

---

## 5. Project layout

```
.
├── pom.xml
├── README.md                                ← this file
├── ROADMAP.md                               ← planned HRGO / FL / RL extensions
├── CLAUDE.md                                ← repo guidance for Claude Code
├── LICENSE                                  ← GPL-3.0-or-later (source code)
├── LICENSE-DATA                             ← CC-BY-4.0 (data under deployment/, paper/)
├── ARTIFACT.md                              ← measured artifact manifest
├── ZENODO.md                                ← Zenodo deposit guide
├── deployment/                              ← RQ1–RQ4 + Online Boutique kit & raw captures
├── comparison-scripts/                      ← Python/PowerShell metric comparators
├── paper/                                   ← LaTeX sources + figures
└── src/
    ├── main/
    │   ├── java/
    │   │   ├── com/example/cspsim/
    │   │   │   ├── CspSimApplication.java   ← @SpringBootApplication (headless)
    │   │   │   ├── cli/ExampleCommandLineRunner.java
    │   │   │   └── simulation/
    │   │   │       ├── ExampleRegistry.java          ← classpath discovery
    │   │   │       └── SimulationRunnerService.java  ← reflective invocation
    │   │   └── org/cloudsimplus/examples/kubernetes/ ← 15 K8s example classes
    │   └── resources/
    │       ├── application.properties
    │       ├── k8s-clusters/                ← topologies for K8sClusterFromYamlExample
    │       └── workload/planetlab/          ← traces for K8sPlanetLabExample
    └── test/java/org/cloudsimplus/examples/kubernetes/
```

---

## 6. How it works

### `ExampleRegistry`
On startup it scans the classpath for every `*.class` under
`org.cloudsimplus.examples/**` (skipping `package-info`). Spring's
`PathMatchingResourcePatternResolver` is used so it works equally in an exploded
layout, inside an IDE, and inside the Spring Boot fat jar (`nested:` URL
protocol). A class is registered if it exposes any of:

- `static void main(String[])`
- `static void main()`
- `void main(String[])`
- `void main()` (Java 25 instance-main style)

### `SimulationRunnerService`
Resolves the requested name (simple or fully qualified), redirects
`System.out` / `System.err` through a tee that copies to both the real console
and an in-memory buffer, then invokes the entry-point via reflection. The
captured output is returned as part of the `RunResult`.

### `ExampleCommandLineRunner`
Implements Spring Boot's `ApplicationRunner` to react to `--list` and
`--example=...`, then calls `System.exit` so CLI callers (pipes, sweeps) get a
clean exit code.

---

## 7. The Kubernetes examples

The `org.cloudsimplus.examples.kubernetes` sub-package is **locally authored** —
the upstream `cloudsimplus-examples` project has no `kubernetes` package. Each
class exercises the upstream `org.cloudsimplus.kubernetes` runtime layer (broker,
scheduler, HPA, VPA, ClusterAutoscaler, controllers) rather than adding simulator
logic of its own. New Kubernetes examples added under this package are
auto-discovered by `ExampleRegistry`.

The orchestration runtime itself ships inside the
`org.cloudsimplus:cloudsimplus` dependency (a WIP development fork; latest public
release is 8.5.7) — fixes to it belong upstream, not in this repo.

### Research scaffolds

`kubernetes/rl/` is a starting point for the reinforcement-learning direction
in `ROADMAP.md`: a tabular Q-learning agent (`QLearningScheduler`) plugged into
the pod-placement loop, learning a load-balancing policy over repeated episodes.
Run it with `--example=K8sQLearningSchedulerExample` (knobs are `-Drl.*`; see the
class Javadoc). It is intentionally simple — for deep RL on larger clusters,
log transitions to CSV and train off-line (ONNX inference back in the scheduler).

---

## 8. Troubleshooting

**`Unsupported class file major version 69`** — Spring Boot < 3.5 cannot read
Java 25 class files. This project pins `3.5.0`; do not downgrade.

**`Illegal char <:> at index 6: nested:\...`** — something tried to walk a
`nested:` jar URL with `java.util.zip.ZipFile`. Use Spring's resource resolver
(already used in `ExampleRegistry`).

**`No usable main() entry-point on ...`** — the example class doesn't expose any
of the four supported `main` shapes.

**Log spam / `CONDITIONS EVALUATION REPORT` / DEBUG flood on startup** — the
cloudsimplus dependency jar bundles its own `logback.xml` with `root=DEBUG`.
Spring Boot adopts the first `logback.xml` it finds as its config (and then
ignores any `logback-spring.xml`), so the dependency's `root=DEBUG` would make
every run dump DEBUG logs and the auto-configuration report. This project ships
`src/main/resources/logback.xml` (it must be named exactly that, not
`-spring`) with the **root** logger at `WARN`; in the fat jar / Maven / IDE,
`BOOT-INF/classes` (this file) precedes `BOOT-INF/lib`, so it wins. CloudSim
Plus also names loggers by *simple class name* (`DatacenterSimple`), so only the
root level — not `application.properties` or the `org.cloudsimplus` logger —
silences the engine. Lower root to `INFO`/`DEBUG` there for event tracing.

---

## 9. License

This repository is **dual-licensed** by content type:

- **Source code — GPL-3.0-or-later** (see [`LICENSE`](LICENSE)). The Java
  sources (`src/`) and the executable comparators/scripts under
  `comparison-scripts/` and `deployment/` derive from and link against
  **CloudSim Plus, which is licensed under the GPLv3**, so this repository's
  code is necessarily GPL-3.0-or-later.
- **Empirical data & paper — CC-BY-4.0** (see [`LICENSE-DATA`](LICENSE-DATA)).
  The raw captures and manifests under `deployment/`, the LaTeX paper sources
  and figures under `paper/`, and the accompanying documentation are licensed
  under Creative Commons Attribution 4.0.

When in doubt about a given file, the more specific of the two licenses applies.
See [`ARTIFACT.md`](ARTIFACT.md) for the measured artifact manifest and
[`ZENODO.md`](ZENODO.md) for how these artifacts are archived to Zenodo.

---

## 10. Citation

This repository is archived on Zenodo. The **concept DOI
`10.5281/zenodo.21251746`** always resolves to the latest version; each release
also mints its own version DOI (shown on the record).

> Zarai, O., Ettazi, W., & Driss, R. (2026). *microservices-sim-poc: Worked
> examples, CLI runner, and reproducibility kit for CloudSim Plus-K8s* (v1.0.2)
> [Software]. Zenodo. https://doi.org/10.5281/zenodo.21251746

If you use the CloudSim Plus-K8s **extension** itself, also cite the fork
(concept DOI `10.5281/zenodo.21251756`). GitHub can render a formatted citation
from [`CITATION.cff`](CITATION.cff) via the **Cite this repository** button. The
accompanying paper is under submission to *Software: Practice and Experience*.
