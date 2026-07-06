# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

The companion artifact to the paper **"CloudSim Plus-K8s: A Contract-Driven Extension for Kubernetes Container Orchestration Simulation"**. It contains:

- **Runnable Kubernetes examples** (`org.cloudsimplus.examples.kubernetes`) that exercise the upstream `org.cloudsimplus.kubernetes` orchestration runtime (pod scheduling, HPA, VPA, cluster autoscaler, workload controllers, M/M/c per-service latency).
- **A thin headless CLI runner** (`com.example.cspsim`) that discovers and executes those examples.

The empirical evaluation, analysis scripts, paper sources, and raw captures are **not** in this repo — they are archived as the Zenodo reproducibility bundle (see "Reproducibility artifacts" below).

This repo was originally a Spring Boot wrapper exposing *all* upstream `cloudsimplus-examples`; it has since been scoped down to only the Kubernetes work that backs the paper. The REST API, Thymeleaf web UI, and Elasticsearch metrics export have been removed.

`ROADMAP.md` describes the longer-term research goal — a runtime game-theoretic orchestrator (HRGO) for microservice resource allocation, plus planned extensions (federated learning, Q-learning/RL schedulers). Those components do not exist yet.

## Build / run

Requires **JDK 25** (sources use Java 25 instance-`main` syntax — Spring Boot 3.5.0 is pinned because earlier versions cannot read class file major version 69).

```bash
mvn clean package -DskipTests        # → target/cloudsimplus-k8s-examples-1.0.0-SNAPSHOT.jar
java -jar target/cloudsimplus-k8s-examples-1.0.0-SNAPSHOT.jar --list
java -jar target/cloudsimplus-k8s-examples-1.0.0-SNAPSHOT.jar --example=K8sHPAExample
mvn spring-boot:run -Dspring-boot.run.arguments=--example=K8sClusterExample
```

The application is headless (`spring.main.web-application-type=none`): the CLI runner executes the requested example and the JVM exits. With no recognized option the Spring context starts and exits without running anything.

`mvn test` runs the JUnit suite under `src/test/java/org/cloudsimplus/examples/kubernetes/`. The `@Tag("benchmark")` performance sweep is excluded from `mvn test`; run it explicitly with `mvn test -Dgroups=benchmark`.

## Architecture

A small discovery-and-run layer over the Kubernetes example classes.

**`com.example.cspsim.simulation.ExampleRegistry`** — `@PostConstruct` scans `classpath*:org/cloudsimplus/examples/**/*.class` using Spring's `PathMatchingResourcePatternResolver` + `CachingMetadataReaderFactory`. The resolver is mandatory: it handles the `nested:` URL protocol used inside the Spring Boot fat jar, where a plain `ZipFile` walk fails with `Illegal char <:> at index 6`. A class is registered only if `hasEntryPoint` finds one of four `main` shapes (static or instance, `()` or `(String[])`); `package-info` is excluded. Two maps (simple-name and FQN) back the lookups.

**`com.example.cspsim.simulation.SimulationRunnerService`** — invokes the example via reflection, preferring `static main(String[])` and falling back through three other shapes. Wraps `System.out`/`System.err` with a `TeePrintStream` so the example's output is preserved on the real console *and* captured into a `ByteArrayOutputStream` returned in the `RunResult`. Resets streams in `finally`. Errors are unwrapped from `InvocationTargetException` before being stringified.

**`com.example.cspsim.cli.ExampleCommandLineRunner`** — Spring's `ApplicationRunner`; reacts to `--list` / `--example=...`, then calls `System.exit` so CLI callers (pipes, sweeps) get a clean exit code.

When adding a new entry-point shape, update both `ExampleRegistry.hasEntryPoint` (so it gets discovered) and `SimulationRunnerService.invokeEntryPoint` (so it gets invoked) — these two methods must agree.

## Constraints when editing

The example classes live under `src/main/java/org/cloudsimplus/examples/kubernetes/`. They are **locally authored** demos of the upstream `org.cloudsimplus.kubernetes` runtime layer (which ships inside the `org.cloudsimplus:cloudsimplus` dependency, not this repo). New Kubernetes-related example classes may be added under this subpackage; they get auto-discovered by `ExampleRegistry` like any other example. New non-example wrapper code goes under `com.example.cspsim`.

The orchestration runtime itself (`org.cloudsimplus.kubernetes.*`) is a Maven dependency — fixes to it belong upstream in the CloudSim Plus fork, not here. The project builds against `org.cloudsimplus:cloudsimplus:9.0.0-SNAPSHOT` (WIP fork; latest public release is 8.5.7).

## Resources

Resource files bundled into the fat jar under `src/main/resources` and loaded via classpath:
- `workload/planetlab/20110303/` — PlanetLab CPU-utilization traces driving `K8sPlanetLabExample`.
- `k8s-clusters/` — YAML cluster topologies and scenarios for `K8sClusterFromYamlExample` (`-Dk8syaml.config=...`).

## Reproducibility artifacts (external)

The empirical evaluation no longer lives in this repo. The deployment kit
(`deployment/` RQ1–RQ4 + `rq2b` + Online Boutique manifests, real-vs-sim CSVs,
findings), the `comparison-scripts/` metric tools (agreement, NRMSD, latency
accuracy), the paper sources, and the review record were moved to the **Zenodo
reproducibility bundle** (sibling `microservices-sim-poc-zenodo/` locally; see
the paper's Data Availability Statement). This repo keeps only the runnable
examples and the headless CLI runner.
