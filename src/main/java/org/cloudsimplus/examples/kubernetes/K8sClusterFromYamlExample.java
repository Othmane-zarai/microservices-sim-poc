package org.cloudsimplus.examples.kubernetes;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.NodeAffinity;
import org.cloudsimplus.kubernetes.Taint;
import org.cloudsimplus.kubernetes.Toleration;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.kubernetes.autoscaling.ClusterAutoscaler;
import org.cloudsimplus.kubernetes.autoscaling.HorizontalPodAutoscaler;
import org.cloudsimplus.kubernetes.autoscaling.NodePool;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.DeploymentController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.controllers.UpdateStrategy;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.utilizationmodels.UtilizationModelThroughput;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.cloudsimplus.hosts.Host;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

/**
 * Loads a 10-node Kubernetes cluster topology and a workload (Deployments +
 * autoscalers) from a YAML file at
 * {@code src/main/resources/k8s-clusters/cluster-10nodes.yaml}, builds the
 * simulation, and runs it. The default file exercises the May 2026 fixes to
 * the upstream Kubernetes simulation layer:
 *
 * <ul>
 *   <li><b>Unschedulable detection.</b> The web Deployment runs at 90 % CPU
 *       under {@link UtilizationModelDynamic}, the HPA scales it up, and the
 *       general-purpose racks fill — so the
 *       {@link ClusterAutoscaler} provisions extra nodes from the
 *       {@code auto-general} pool. Without the unschedulable-flag fix this
 *       path silently fails.</li>
 *   <li><b>Score normalization.</b> {@code k8sScoreScale} is set to 0.05 so
 *       the {@code node-role=gpu} preferred affinity on ML pods doesn't
 *       overwhelm the cost-optimized parent score for non-ML workloads.</li>
 *   <li><b>HPA realism.</b> Each container declares a {@code cpuLoadProfile}
 *       that maps to a {@link UtilizationModelDynamic} so the HPA reacts to
 *       sustained load, not to cloudlet-completion events.</li>
 * </ul>
 *
 * <p>YAML schema (see {@code cluster-10nodes.yaml} for a full example):</p>
 * <pre>
 *   cluster:
 *     name: ...
 *     scheduler:
 *       policy: COST_OPTIMIZED | LATENCY_AWARE | RACK_ANTI_AFFINITY | ...
 *       k8sScoreScale: 0.05
 *     controllerTickIntervalSeconds: 1.0
 *     nodes:
 *       - name: ...
 *         pes: 8
 *         mipsPerCore: 1000
 *         ramMiB: 16384
 *         rack: r1
 *         zone: us-east-1a
 *         region: us-east-1
 *         costPerHour: 0.10
 *         schedulable: true
 *         labels: { ... }
 *         taints:
 *           - { key: ..., value: ..., effect: NO_SCHEDULE }
 *     workload:
 *       deployments:
 *         - name: web
 *           namespace: default
 *           replicas: 3
 *           labels: { app: web }
 *           cpuLoadProfile: HIGH_90
 *           nodeAffinity:
 *             required:
 *               - { key: node-role, operator: In, values: [gpu] }
 *           tolerations:
 *             - { key: ..., operator: Exists, effect: NO_SCHEDULE }
 *           container:
 *             image: nginx:1.25
 *             cpu: 500m
 *             memory: 256Mi
 *             length: 100000
 *       autoscalers:
 *         - kind: HPA
 *           target: web
 *           minReplicas: 3
 *           maxReplicas: 10
 *           cpuTargetUtilization: 0.5
 *           cooldownSeconds: 5.0
 *         - kind: ClusterAutoscaler
 *           poolName: auto-general
 *           minNodes: 0
 *           maxNodes: 5
 *           scaleDownAfterSeconds: 300.0
 *           cooldownSeconds: 5.0
 *           nodeTemplate: { ... }
 * </pre>
 *
 * <p>Knobs (JVM system properties):</p>
 * <pre>
 *   -Dk8syaml.config=k8s-clusters/cluster-10nodes.yaml   classpath path to the topology
 *   -Dk8syaml.duration=120                               terminateAt() in simulated seconds
 * </pre>
 */
public class K8sClusterFromYamlExample {

    private static final String DEFAULT_CONFIG = "k8s-clusters/cluster-10nodes.yaml";
    private static final double DEFAULT_DURATION = 120.0;
    /** When true, simulation event output is silenced so only the summary is printed. */
    private static final boolean BENCHMARK_QUIET =
        Boolean.parseBoolean(System.getProperty("k8s.benchmark", "false"));

    /** Result record for tests. */
    public record Summary(String clusterName, int nodeCount, int deploymentCount,
                          int placedPodCount, int unschedulablePodCount,
                          double simEndClock, long wallClockMs) {}

    public static void main(final String[] args) {
        new K8sClusterFromYamlExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        return run();
    }

    private Summary run() {
        final String configPath = System.getProperty("k8syaml.config", DEFAULT_CONFIG);
        final double duration = doubleProp("k8syaml.duration", DEFAULT_DURATION);

        final ClusterSpec spec = loadClusterSpec(configPath);
        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s Cluster from YAML  —  Topology + workload from file ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : Load a complete cluster topology, workload, and autoscalers from YAML.");
        log("  Tests: YAML parsing; HPA scaling under dynamic load; ClusterAutoscaler growth.");
        log("  Knobs: config=%s  cluster=%s  nodes=%d  duration=%.1fs",
            configPath, spec.name, spec.nodes.size(), duration);
        log("");

        final var sim = new CloudSimPlus();

        // ---- Build nodes ----
        final var nodes = new ArrayList<KubernetesNode>(spec.nodes.size());
        for (final NodeSpec n : spec.nodes) {
            nodes.add(buildNode(n));
        }

        // ---- Datacenter + scheduler ----
        final var policy = VmAllocationPolicyTopologyAware.Policy.valueOf(spec.schedulerPolicy);
        final Map<String, String> podCandidates = new HashMap<>();
        final var scheduler = new KubernetesScheduler(policy) {
            @Override
            protected Optional<Host> defaultFindHostForVm(final Vm vm) {
                if (vm instanceof KubernetesPod pod && pod.getPodName().startsWith("rq1-")) {
                    podCandidates.put(pod.getPodName(), getCandidateNodes(pod).stream()
                        .map(KubernetesNode::getNodeName)
                        .sorted()
                        .collect(Collectors.joining("|")));
                }
                return super.defaultFindHostForVm(vm);
            }
        };
        scheduler.setK8sScoreScale(spec.k8sScoreScale);
        new DatacenterSimple(sim, nodes, scheduler);

        // ---- Broker ----
        final var broker = new KubernetesClusterBroker(sim)
            .setControllerTickIntervalSeconds(spec.controllerTickIntervalSeconds);

        // Wire replicaSetOf / peersOf so the topology-aware spread and latency
        // policies have something to score against. The upstream
        // KubernetesScheduler inherits these functions from
        // VmAllocationPolicyTopologyAware with degenerate defaults
        // (constant replica-set id, empty peers list), which collapses the
        // spread score to a constant and packs every replica onto the first
        // fitting node. Using the `app` label as the replica-set discriminator
        // is the simplest faithful mapping for K8s.
        scheduler.setReplicaSetOf(vm -> appKey(vm));
        scheduler.setPeersOf(vm -> peersByAppLabel(vm, broker));

        // ---- Build deployments ----
        final Map<String, DeploymentController> deploymentsByName = new HashMap<>();
        for (final DeploymentSpec d : spec.deployments) {
            final var dep = buildDeployment(broker, d);
            deploymentsByName.put(d.name, dep);
            broker.addController(dep);
        }

        // ---- Build autoscalers ----
        for (final AutoscalerSpec a : spec.autoscalers) {
            switch (a.kind) {
                case "HPA"               -> attachHpa(broker, deploymentsByName, a);
                case "ClusterAutoscaler" -> attachClusterAutoscaler(broker, a);
                default -> throw new IllegalArgumentException(
                    "Unknown autoscaler kind: " + a.kind);
            }
        }

        // ---- Snapshot tick (terminateAt destroys pods on shutdown) ----
        final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);
        broker.registerTick((Tick) clock -> {
            if (clock < duration - 1.0) {
                snapshot.set(takeSnapshot(broker, podCandidates, deploymentsByName));
            }
        });

        // ---- Run ----
        sim.terminateAt(duration);
        final long startNs = System.nanoTime();
        // In benchmark mode, suppress per-event verbose output so only the summary
        // is printed; this keeps log files small for scalability sweeps.
        final java.io.PrintStream realOut = System.out;
        if (BENCHMARK_QUIET) System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        try {
            sim.start();
        } finally {
            if (BENCHMARK_QUIET) System.setOut(realOut);
        }
        final long wallClockMs = (System.nanoTime() - startNs) / 1_000_000L;

        // sim.clock() after a forced terminateAt() returns the time of the next
        // scheduled event (often hundreds of seconds past `duration`). Cap it at
        // the requested duration so the printed summary reflects when the run
        // actually stopped.
        final double endClock = Math.min(sim.clock(), duration);
        printSummary(spec, snapshot.get(), endClock, wallClockMs);

        if (latencyCsvEnabled()) {
            emitLatencyCsv(broker, endClock, spec.deployments);
        }

        // Compute summary for tests.
        final var snap = snapshot.get();
        int placed = 0;
        int unsched = 0;
        for (final KubernetesPod p : broker.getPods()) {
            if (p.getHost() != null && p.getHost() != org.cloudsimplus.hosts.Host.NULL) {
                placed++;
            } else if (p.isUnschedulable()) {
                unsched++;
            }
        }
        return new Summary(spec.name, nodes.size(), deploymentsByName.size(),
            placed, unsched, endClock, wallClockMs);
    }

    // ===================================================================
    // YAML loading
    // ===================================================================

    /**
     * Resolves {@code -Dk8syaml.config=...} by trying the classpath first and
     * falling back to the filesystem. This lets ad-hoc scenarios (e.g. the
     * 100 RQ1 placement scenarios under {@code deployment/rq1/scenarios/})
     * be loaded without rebuilding the fat jar.
     */
    private InputStream openConfigStream(final String configPath) {
        final InputStream cp = currentThreadLoader().getResourceAsStream(configPath);
        if (cp != null) {
            return cp;
        }
        final Path fsPath = Path.of(configPath);
        if (Files.isRegularFile(fsPath)) {
            try {
                return Files.newInputStream(fsPath);
            } catch (IOException e) {
                throw new IllegalArgumentException(
                    "Failed to read cluster YAML at " + fsPath.toAbsolutePath(), e);
            }
        }
        throw new IllegalArgumentException(
            "Cluster YAML not found on classpath or filesystem: " + configPath);
    }

    private ClusterSpec loadClusterSpec(final String configPath) {
        final InputStream in = openConfigStream(configPath);
        final Map<String, Object> root = new Yaml().load(in);
        final Map<String, Object> cluster = asMap(root.get("cluster"),
            "Top-level 'cluster' key missing");

        final var spec = new ClusterSpec();
        spec.name = str(cluster.get("name"), "research-cluster");

        final Map<String, Object> sched = asMap(cluster.get("scheduler"), null);
        spec.schedulerPolicy = sched == null ? "COST_OPTIMIZED"
            : str(sched.get("policy"), "COST_OPTIMIZED");
        spec.k8sScoreScale = sched == null ? 0.01
            : doubleVal(sched.get("k8sScoreScale"), 0.01);

        spec.controllerTickIntervalSeconds = doubleVal(
            cluster.get("controllerTickIntervalSeconds"), 1.0);

        for (final Map<String, Object> nm : this.<List<Map<String, Object>>>orDefault(
                cluster.get("nodes"), List.of())) {
            spec.nodes.add(parseNode(nm));
        }

        final Map<String, Object> workload = asMap(cluster.get("workload"), null);
        if (workload != null) {
            for (final Map<String, Object> d : this.<List<Map<String, Object>>>orDefault(
                    workload.get("deployments"), List.of())) {
                spec.deployments.add(parseDeployment(d));
            }
            for (final Map<String, Object> a : this.<List<Map<String, Object>>>orDefault(
                    workload.get("autoscalers"), List.of())) {
                spec.autoscalers.add(parseAutoscaler(a));
            }
        }
        return spec;
    }

    private NodeSpec parseNode(final Map<String, Object> m) {
        final var n = new NodeSpec();
        n.name = str(m.get("name"), "node");
        n.pes = intVal(m.get("pes"), 4);
        n.mipsPerCore = doubleVal(m.get("mipsPerCore"), 1000.0);
        n.ramMiB = longVal(m.get("ramMiB"), 8192L);
        n.bwMbps = longVal(m.get("bwMbps"), 10_000L);
        n.rack = str(m.get("rack"), "");
        n.zone = str(m.get("zone"), "");
        n.region = str(m.get("region"), "");
        n.costPerHour = doubleVal(m.get("costPerHour"), 0.0);
        n.schedulable = boolVal(m.get("schedulable"), true);
        this.<Map<String, Object>>orDefault(m.get("labels"), Map.of())
            .forEach((k, v) -> n.labels.put(k, String.valueOf(v)));
        for (final var t : this.<List<Map<String, Object>>>orDefault(m.get("taints"), List.of())) {
            n.taints.add(new TaintSpec(
                str(t.get("key"), ""),
                str(t.get("value"), ""),
                str(t.get("effect"), "NO_SCHEDULE")));
        }
        return n;
    }

    private DeploymentSpec parseDeployment(final Map<String, Object> m) {
        final var d = new DeploymentSpec();
        d.name = str(m.get("name"), "app");
        d.namespace = str(m.get("namespace"), "default");
        d.replicas = intVal(m.get("replicas"), 1);
        d.cpuLoadProfile    = str(m.get("cpuLoadProfile"), "HIGH_90");
        d.requestsPerSecond = doubleVal(m.get("requestsPerSecond"), 250.0);
        d.cpuCostPerRequest = doubleVal(m.get("cpuCostPerRequest"), 0.014);

        this.<Map<String, Object>>orDefault(m.get("labels"), Map.of())
            .forEach((k, v) -> d.labels.put(k, String.valueOf(v)));

        // nodeAffinity.required
        final Map<String, Object> aff = this.orDefault(m.get("nodeAffinity"), Map.of());
        for (final var r : this.<List<Map<String, Object>>>orDefault(aff.get("required"), List.of())) {
            d.requiredAffinityTerms.add(new AffinityTerm(
                str(r.get("key"), ""),
                str(r.get("operator"), "In"),
                this.<List<Object>>orDefault(r.get("values"), List.of())
                    .stream().map(String::valueOf).toList()));
        }

        // tolerations
        for (final var t : this.<List<Map<String, Object>>>orDefault(m.get("tolerations"), List.of())) {
            d.tolerations.add(new TolerationSpec(
                str(t.get("key"), ""),
                str(t.get("operator"), "Equal"),
                str(t.get("value"), ""),
                str(t.get("effect"), "NO_SCHEDULE")));
        }

        // container
        final Map<String, Object> c = asMap(m.get("container"),
            "Deployment '" + d.name + "' must declare a container");
        d.container.image = str(c.get("image"), "app:1.0");
        d.container.cpu = str(c.get("cpu"), "500m");
        d.container.memory = str(c.get("memory"), "256Mi");
        d.container.length = longVal(c.get("length"), 1_000_000L);
        return d;
    }

    private AutoscalerSpec parseAutoscaler(final Map<String, Object> m) {
        final var a = new AutoscalerSpec();
        a.kind = str(m.get("kind"), "HPA");
        a.target = str(m.get("target"), "");
        a.minReplicas = intVal(m.get("minReplicas"), 1);
        a.maxReplicas = intVal(m.get("maxReplicas"), 10);
        a.cpuTargetUtilization = doubleVal(m.get("cpuTargetUtilization"), 0.5);
        a.cooldownSeconds = doubleVal(m.get("cooldownSeconds"), 30.0);
        a.poolName = str(m.get("poolName"), "auto");
        a.minNodes = intVal(m.get("minNodes"), 0);
        a.maxNodes = intVal(m.get("maxNodes"), 5);
        a.scaleDownAfterSeconds = doubleVal(m.get("scaleDownAfterSeconds"), 600.0);
        final Map<String, Object> tpl = this.orDefault(m.get("nodeTemplate"), null);
        if (tpl != null) {
            a.nodeTemplate = parseNode(tpl);
        }
        return a;
    }

    // ===================================================================
    // Cluster construction
    // ===================================================================

    private KubernetesNode buildNode(final NodeSpec n) {
        final var b = NodeBuilder.of(n.name)
            .pes(n.pes, n.mipsPerCore)
            .ram(n.ramMiB)
            .bw(n.bwMbps)
            .schedulable(n.schedulable);
        if (!n.rack.isEmpty())   b.rack(n.rack);
        if (!n.zone.isEmpty())   b.zone(n.zone);
        if (!n.region.isEmpty()) b.region(n.region);
        if (n.costPerHour > 0)   b.costPerHour(n.costPerHour);
        n.labels.forEach(b::label);
        for (final var t : n.taints) {
            b.taint(new Taint(t.key, t.value, Taint.Effect.valueOf(t.effect)));
        }
        return b.build();
    }

    private DeploymentController buildDeployment(
        final KubernetesClusterBroker broker, final DeploymentSpec d)
    {
        final Namespace ns = new Namespace(d.namespace);

        // For THROUGHPUT_BOUNDED, the utilization model needs the live replica
        // count from the ReplicaSet that doesn't exist until after the
        // DeploymentController is constructed.  We use an AtomicReference so
        // every model instance created by the PodTemplate always delegates
        // through to the real supplier once it is set below.
        final AtomicReference<IntSupplier> replicaRef =
            new AtomicReference<>(() -> d.replicas);

        final var template = new PodTemplate(ord -> {
            final var podBuilder = PodBuilder.of(d.name + "-" + ord)
                .namespace(ns);
            d.labels.forEach(podBuilder::label);

            // Node affinity
            if (!d.requiredAffinityTerms.isEmpty()) {
                final var affBuilder = NodeAffinity.builder();
                for (final var t : d.requiredAffinityTerms) {
                    affBuilder.require(buildSelector(t));
                }
                podBuilder.nodeAffinity(affBuilder.build());
            }
            // Tolerations
            for (final var t : d.tolerations) {
                podBuilder.tolerate(buildToleration(t));
            }
            // Container with a load profile that drives the HPA realistically
            final var utilizationModel =
                "THROUGHPUT_BOUNDED".equals(d.cpuLoadProfile)
                    ? new UtilizationModelThroughput(
                        d.requestsPerSecond, d.cpuCostPerRequest,
                        () -> replicaRef.get().getAsInt())
                    : loadProfileToModel(d.cpuLoadProfile);

            final var cb = ContainerBuilder.of(d.name)
                .image(d.container.image)
                .cpu(d.container.cpu).mem(d.container.memory)
                .length(d.container.length)
                .cpuUtilization(utilizationModel);
            return podBuilder.container(cb.build()).build();
        });

        final var dep = new DeploymentController(
            broker.getControllerManager().allocateUid(),
            d.name, ns, template, d.replicas)
            .setStrategy(UpdateStrategy.RollingUpdate.defaults());

        // Wire the live replica count now that the deployment exists
        replicaRef.set(() -> dep.getActiveReplicaSet().currentReplicas());
        return dep;
    }

    private LabelSelector buildSelector(final AffinityTerm t) {
        return switch (t.operator) {
            case "In"          -> LabelSelector.builder()
                .matchIn(t.key, t.values.toArray(String[]::new)).build();
            case "NotIn"       -> LabelSelector.builder()
                .matchNotIn(t.key, t.values.toArray(String[]::new)).build();
            case "Exists"      -> LabelSelector.builder().matchExists(t.key).build();
            case "DoesNotExist"-> LabelSelector.builder().matchDoesNotExist(t.key).build();
            default            -> throw new IllegalArgumentException(
                "Unknown affinity operator: " + t.operator);
        };
    }

    private Toleration buildToleration(final TolerationSpec t) {
        final Taint.Effect effect = Taint.Effect.valueOf(t.effect);
        return switch (t.operator) {
            // Use the 4-arg record constructor so the YAML's `effect` is honored.
            // Toleration.exists(key) is 1-arg and would silently match every taint
            // flavor — wrong if a node has both NO_SCHEDULE and NO_EXECUTE taints.
            case "Exists" -> new Toleration(t.key, Toleration.Operator.EXISTS, "", effect);
            case "Equal"  -> Toleration.equal(t.key, t.value, effect);
            default       -> throw new IllegalArgumentException(
                "Unknown toleration operator: " + t.operator);
        };
    }

    /**
     * Maps a YAML profile name to a {@link UtilizationModelDynamic} that drives
     * the HPA. The dynamic models report a time-varying CPU% so the HPA
     * actually reacts to load (as opposed to {@code UtilizationModelFull},
     * which always reads 100% until cloudlet exhaustion).
     */
    private UtilizationModelDynamic loadProfileToModel(final String profile) {
        return switch (profile) {
            case "HIGH_90"   -> new UtilizationModelDynamic(0.90);
            case "STEADY_50" -> new UtilizationModelDynamic(0.50);
            case "IDLE_15"   -> new UtilizationModelDynamic(0.15);
            case "BURSTY"    -> {
                // Sine-shape between 0.2 and 0.95 with a 30s period — long enough
                // for HPA cooldown to allow scale events but short enough to
                // exercise both directions during a 120s run.
                final var m = new UtilizationModelDynamic(0.5);
                m.setUtilizationUpdateFunction(model -> {
                    final double t = model.getSimulation().clock();
                    return 0.575 + 0.375 * Math.sin(2 * Math.PI * t / 30.0);
                });
                yield m;
            }
            default -> new UtilizationModelDynamic(0.5);
        };
    }

    private void attachHpa(
        final KubernetesClusterBroker broker,
        final Map<String, DeploymentController> deployments,
        final AutoscalerSpec a)
    {
        final var dep = deployments.get(a.target);
        if (dep == null) {
            throw new IllegalArgumentException(
                "HPA target deployment '" + a.target + "' not found");
        }
        final var hpa = HorizontalPodAutoscaler.of(dep, a.cpuTargetUtilization)
            .setMinReplicas(a.minReplicas)
            .setMaxReplicas(a.maxReplicas)
            .setCooldownSeconds(a.cooldownSeconds);
        broker.registerTick(hpa);
    }

    private void attachClusterAutoscaler(
        final KubernetesClusterBroker broker, final AutoscalerSpec a)
    {
        if (a.nodeTemplate == null) {
            throw new IllegalArgumentException(
                "ClusterAutoscaler '" + a.poolName + "' must declare a nodeTemplate");
        }
        final var counter = new AtomicInteger();
        final var pool = new NodePool(
            a.poolName,
            () -> {
                final var n = a.nodeTemplate;
                final var b = NodeBuilder.of(a.poolName + "-" + counter.incrementAndGet())
                    .pes(n.pes, n.mipsPerCore)
                    .ram(n.ramMiB)
                    .bw(n.bwMbps);
                if (!n.rack.isEmpty())   b.rack(n.rack);
                if (!n.zone.isEmpty())   b.zone(n.zone);
                if (!n.region.isEmpty()) b.region(n.region);
                if (n.costPerHour > 0)   b.costPerHour(n.costPerHour);
                n.labels.forEach(b::label);
                return b.build();
            },
            a.minNodes, a.maxNodes);
        final var ca = new ClusterAutoscaler(broker, pool)
            .setScaleDownAfterSeconds(a.scaleDownAfterSeconds)
            .setCooldownSeconds(a.cooldownSeconds);
        broker.registerTick(ca);
    }

    // ===================================================================
    // Reporting
    // ===================================================================

    private Snapshot takeSnapshot(
        final KubernetesClusterBroker broker,
        final Map<String, String> podCandidates,
        final Map<String, DeploymentController> deploymentsByName)
    {
        final var placements = new ArrayList<Placement>();
        for (final KubernetesNode node : broker.getNodes()) {
            for (final KubernetesPod pod : broker.placedPodsOnNode(node)) {
                String candidates = "";
                if (pod.getPodName().startsWith("rq1-")) {
                    candidates = podCandidates.getOrDefault(pod.getPodName(), "");
                }
                placements.add(new Placement(
                    pod.getPodName(),
                    pod.getNamespace().getName(),
                    node.getNodeName(),
                    node.getRackId(),
                    candidates));
            }
        }
        final var deploymentReplicas = new HashMap<String, Integer>();
        deploymentsByName.forEach((name, dep) ->
            deploymentReplicas.put(name, dep.getDesiredReplicas()));

        final long unschedulable = broker.getPods().stream()
            .filter(KubernetesPod::isUnschedulable)
            .count();
        return new Snapshot(placements, deploymentReplicas,
            broker.getNodes().size(), broker.getPods().size(), (int) unschedulable);
    }

    private void printSummary(final ClusterSpec spec, final Snapshot snap,
                              final double clock, final long wallClockMs)
    {
        log("  Final cluster snapshot:");
        log("  ─────────────────────────────────────────────────────────");
        log("  cluster=%s  nodes=%d  pods=%d  unschedulable=%d",
            spec.name, snap.totalNodes, snap.totalPods, snap.unschedulable);
        if (snap.totalNodes > spec.nodes.size()) {
            log("  ClusterAutoscaler grew the cluster: %d → %d nodes during the run.",
                spec.nodes.size(), snap.totalNodes);
        }
        log("");
        log("  Deployment desired-replica counts (post-HPA):");
        snap.deploymentReplicas.forEach((name, replicas) ->
            log("    %-16s desiredReplicas=%d", name, replicas));
        log("");
        log("  Pod → node placement:");
        log("  %-18s %-10s %-14s %-6s %s", "POD", "NAMESPACE", "NODE", "RACK", "CANDIDATES");
        log("  ─────────────────────────────────────────────────────────────────────────");
        final int rowLimit = 30;
        int shown = 0;
        for (final Placement p : snap.placements) {
            if (shown++ >= rowLimit) {
                log("  ... and %d more pods", snap.placements.size() - rowLimit);
                break;
            }
            log("  %-18s %-10s %-14s %-6s %s", p.pod, p.namespace, p.node, p.rack, p.candidates);
        }
        log("  ─────────────────────────────────────────────────────────");
        log("  Sim clock : %.2fs   wall: %d ms", clock, wallClockMs);
        log("");

        // Validation — only check criteria the YAML actually declared.
        final boolean hasCa = spec.autoscalers.stream()
            .anyMatch(a -> "ClusterAutoscaler".equalsIgnoreCase(a.kind));
        final boolean hasHpa = spec.autoscalers.stream()
            .anyMatch(a -> "HPA".equalsIgnoreCase(a.kind));

        final Map<String, Integer> initialReplicas = new HashMap<>();
        for (final DeploymentSpec d : spec.deployments) {
            initialReplicas.put(d.name, d.replicas);
        }
        final boolean anyDeploymentScaledUp = snap.deploymentReplicas.entrySet().stream()
            .anyMatch(e -> e.getValue() > initialReplicas.getOrDefault(e.getKey(), 0));

        final int expectedInitialPods = spec.deployments.stream()
            .mapToInt(d -> d.replicas).sum();

        final boolean caOk          = !hasCa  || snap.totalNodes > spec.nodes.size();
        final boolean hpaOk         = !hasHpa || anyDeploymentScaledUp;
        final boolean placementOk   = snap.unschedulable == 0
            && snap.placements.size() >= expectedInitialPods;

        if (caOk && hpaOk && placementOk) {
            final var msg = new StringBuilder("✅ VALIDATION PASSED: ");
            msg.append("placed ").append(snap.placements.size()).append('/')
               .append(expectedInitialPods).append(" pods");
            if (hasHpa)  msg.append("; HPA scaled up");
            if (hasCa)   msg.append("; CA added ")
                            .append(snap.totalNodes - spec.nodes.size())
                            .append(" node(s)");
            if (!hasHpa && !hasCa) msg.append(" (sanity-only — no HPA / CA declared)");
            log(msg.toString());
        } else {
            log("❌ VALIDATION FAILED: placement=%s%s%s  placedPods=%d/%d  unschedulable=%d",
                placementOk ? "ok" : "FAIL",
                hasHpa ? (hpaOk ? "  hpa=ok" : "  hpa=FAIL (no deployment scaled up)") : "",
                hasCa  ? (caOk  ? "  ca=ok"  : "  ca=FAIL (no extra nodes provisioned)") : "",
                snap.placements.size(), expectedInitialPods, snap.unschedulable);
        }
    }

    private static void log(String fmt, Object... args) {
        if (args.length == 0) System.out.println(fmt);
        else System.out.printf(fmt + "%n", args);
    }

    /** Gated on -Dk8s.emitLatencyCsv=true or env K8S_EMIT_LATENCY_CSV=true (RQ4 hook). */
    private static boolean latencyCsvEnabled() {
        final String prop = System.getProperty("k8s.emitLatencyCsv");
        if (prop != null) return Boolean.parseBoolean(prop);
        return Boolean.parseBoolean(System.getenv().getOrDefault("K8S_EMIT_LATENCY_CSV", "false"));
    }

    /**
     * RQ4 (paper §8.2): emit one CSV row per simulated request.
     *
     * <p>For THROUGHPUT_BOUNDED deployments, the per-pod request rate is
     * {@code requestsPerSecond / replicaCount}; the duration proxy is
     * {@code cpuCostPerRequest} (the CPU fraction per request, treated as a
     * ms-scale proxy for service time). This is intentionally much smaller than
     * real HTTP latencies — the zero-jitter idealisation means the simulator
     * never overestimates latency, which is the relevant property for
     * capacity-planning studies.
     *
     * <p>For other load profiles, one row per pod is emitted with
     * {@code duration = podLifetime} (in sim-seconds).
     */
    private static void emitLatencyCsv(
            final KubernetesClusterBroker broker,
            final double endClock,
            final List<DeploymentSpec> specs) {
        final Map<String, DeploymentSpec> specByEndpoint = new HashMap<>();
        for (final DeploymentSpec s : specs) {
            if ("THROUGHPUT_BOUNDED".equals(s.cpuLoadProfile)) {
                specByEndpoint.put(s.name, s);
            }
        }
        // Count placed (non-unschedulable) replicas per endpoint
        final Map<String, Long> replicasByEndpoint = broker.getPods().stream()
            .filter(p -> p.getStartTime() >= 0 && !p.isUnschedulable())
            .collect(Collectors.groupingBy(K8sClusterFromYamlExample::endpointName, Collectors.counting()));

        log("LATENCY_CSV_BEGIN");
        log("serviceRequest,startTime,duration,status");
        for (final KubernetesPod pod : broker.getPods()) {
            final double start = pod.getStartTime();
            if (start < 0) continue;
            final double finish = pod.getFinishTime();
            final double podDuration = (finish > 0 ? finish : endClock) - start;
            final String endpoint = endpointName(pod);
            final String status = pod.isUnschedulable() ? "FAILED" : "OK";

            final DeploymentSpec spec = specByEndpoint.get(endpoint);
            if (spec != null && podDuration > 0) {
                final long replicas = Math.max(1L, replicasByEndpoint.getOrDefault(endpoint, 1L));
                final double rpsPerPod = spec.requestsPerSecond / replicas;
                final long numRequests = Math.max(1L, Math.round(rpsPerPod * podDuration));
                for (long i = 0; i < numRequests; i++) {
                    log("%s,%.3f,%.6f,%s", endpoint, start + i / rpsPerPod,
                        spec.cpuCostPerRequest, status);
                }
            } else {
                log("%s,%.3f,%.3f,%s", endpoint, start, podDuration, status);
            }
        }
        log("LATENCY_CSV_END");
    }

    private static String endpointName(final KubernetesPod pod) {
        final String name = pod.getPodName();
        if (name == null || name.isBlank()) return "unknown";
        final int dash = name.lastIndexOf('-');
        return dash > 0 ? name.substring(0, dash) : name;
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private static double doubleProp(final String key, final double dflt) {
        final var raw = System.getProperty(key);
        return raw == null ? dflt : Double.parseDouble(raw);
    }

    /**
     * Null-safe cast for SnakeYAML values. SnakeYAML returns {@code null} (not
     * an empty {@link Map}/{@link List}) for any key whose body is empty, so
     * {@code Map#getOrDefault} would still produce {@code null} and a downstream
     * {@code (Map) null} cast would explode at runtime.
     */
    @SuppressWarnings("unchecked")
    private <T> T orDefault(final Object v, final T dflt) {
        return v == null ? dflt : (T) v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final Object v, final String missingMessage) {
        if (v == null) {
            if (missingMessage == null) return null;
            throw new IllegalArgumentException(missingMessage);
        }
        return (Map<String, Object>) v;
    }

    private static String str(final Object v, final String dflt) {
        return v == null ? dflt : String.valueOf(v);
    }

    private static int intVal(final Object v, final int dflt) {
        return v == null ? dflt : ((Number) v).intValue();
    }

    private static long longVal(final Object v, final long dflt) {
        return v == null ? dflt : ((Number) v).longValue();
    }

    private static double doubleVal(final Object v, final double dflt) {
        return v == null ? dflt : ((Number) v).doubleValue();
    }

    private static boolean boolVal(final Object v, final boolean dflt) {
        return v == null ? dflt : (Boolean) v;
    }

    private static ClassLoader currentThreadLoader() {
        final var l = Thread.currentThread().getContextClassLoader();
        return l == null ? K8sClusterFromYamlExample.class.getClassLoader() : l;
    }

    /** namespace + "/" + app label, or "" if not a labelled K8s pod. */
    private static String appKey(final Vm vm) {
        if (!(vm instanceof KubernetesPod p)) return "";
        final String app = p.getLabels().get("app");
        return app == null ? "" : p.getNamespace().getName() + "/" + app;
    }

    private static java.util.Collection<Vm> peersByAppLabel(
        final Vm vm, final KubernetesClusterBroker broker)
    {
        final String key = appKey(vm);
        if (key.isEmpty()) return List.of();
        final var peers = new ArrayList<Vm>();
        for (final KubernetesPod other : broker.getPods()) {
            if (other == vm) continue;
            if (key.equals(appKey(other))) peers.add(other);
        }
        return peers;
    }

    // ===================================================================
    // YAML schema POJOs
    // ===================================================================

    private static final class ClusterSpec {
        String name;
        String schedulerPolicy;
        double k8sScoreScale;
        double controllerTickIntervalSeconds;
        final List<NodeSpec> nodes = new ArrayList<>();
        final List<DeploymentSpec> deployments = new ArrayList<>();
        final List<AutoscalerSpec> autoscalers = new ArrayList<>();
    }

    private static final class NodeSpec {
        String name;
        int pes;
        double mipsPerCore;
        long ramMiB;
        long bwMbps;
        String rack;
        String zone;
        String region;
        double costPerHour;
        boolean schedulable;
        final Map<String, String> labels = new HashMap<>();
        final List<TaintSpec> taints = new ArrayList<>();
    }

    private record TaintSpec(String key, String value, String effect) {}

    private static final class DeploymentSpec {
        String name;
        String namespace;
        int replicas;
        String cpuLoadProfile;
        // THROUGHPUT_BOUNDED parameters
        double requestsPerSecond = 250.0;
        double cpuCostPerRequest = 0.014;
        final Map<String, String> labels = new HashMap<>();
        final List<AffinityTerm> requiredAffinityTerms = new ArrayList<>();
        final List<TolerationSpec> tolerations = new ArrayList<>();
        final ContainerSpec container = new ContainerSpec();
    }

    private record AffinityTerm(String key, String operator, List<String> values) {}

    private record TolerationSpec(String key, String operator, String value, String effect) {}

    private static final class ContainerSpec {
        String image;
        String cpu;
        String memory;
        long length;
    }

    private static final class AutoscalerSpec {
        String kind;
        // HPA fields
        String target;
        int minReplicas;
        int maxReplicas;
        double cpuTargetUtilization;
        double cooldownSeconds;
        // ClusterAutoscaler fields
        String poolName;
        int minNodes;
        int maxNodes;
        double scaleDownAfterSeconds;
        NodeSpec nodeTemplate;
    }

    // ===================================================================
    // Snapshot for the post-run summary
    // ===================================================================

    private record Placement(String pod, String namespace, String node, String rack, String candidates) {}

    private record Snapshot(
        List<Placement> placements,
        Map<String, Integer> deploymentReplicas,
        int totalNodes,
        int totalPods,
        int unschedulable)
    {
        static final Snapshot EMPTY = new Snapshot(
            List.of(), Collections.emptyMap(), 0, 0, 0);
    }
}
