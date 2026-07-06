package org.cloudsimplus.examples.kubernetes;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesContainer;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.DeploymentController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.controllers.UpdateStrategy;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.utilizationmodels.UtilizationModelPlanetLab;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trace-driven counterpart to {@link K8sClusterExample}. Each pod's container CPU
 * usage is driven by a real PlanetLab CPU-utilization trace from
 * {@code src/main/resources/workload/planetlab/20110303/} (each file is one
 * machine, 24h sampled every 300s — 0–100 %). With 100 replicas over a
 * simulated hour this produces the kind of heterogeneous load curve you can't
 * get from {@link org.cloudsimplus.utilizationmodels.UtilizationModelFull}
 * (the constant-100% default applied by {@link ContainerBuilder}).
 *
 * <p>Two non-obvious wiring details:</p>
 * <ul>
 *   <li>{@code KubernetesContainer} extends {@code CloudletSimple}, so we can
 *       call {@link org.cloudsimplus.kubernetes.KubernetesContainer#setUtilizationModelCpu}
 *       on the result of {@code ContainerBuilder.build()} to swap in the trace.</li>
 *   <li>The Datacenter must have its scheduling interval matched to the trace's
 *       sampling interval (300s) — otherwise CPU is only re-sampled on simulation
 *       events and the trace's curve gets flattened. Same caveat as
 *       {@code PlanetLabExample1}.</li>
 * </ul>
 *
 * Knobs (JVM system properties — defaults model "100 users for 1 hour"):
 *   -Dk8s.nodes=30                  worker nodes (each 4 PE × 1000 MIPS, 8 GiB)
 *   -Dk8s.replicas=100              Deployment desired replicas
 *   -Dk8s.duration=3600             terminateAt() in simulated seconds
 *   -Dk8s.tickInterval=1.0          broker controller-tick interval
 *   -Dk8s.schedulingInterval=300    DC scheduling interval; must equal trace step
 *   -Dk8s.containerLengthMI=50000000  per-container work, sized so pods outlive
 *                                     the run under realistic (sub-100%) load
 *   -Dk8s.cpuMultiplier=1.0         scales every trace value (clipped at 1.0).
 *                                   PlanetLab traces are mostly idle (~12% avg);
 *                                   try 4.5 for a realistic ~55% loaded cluster
 *                                   that actually exercises the scheduler.
 */
public class K8sPlanetLabExample {

    /**
     * Subset of trace files shipped under {@code workload/planetlab/20110303/}.
     * Pods are assigned a trace by {@code ordinal % TRACES.size()}; with 100
     * replicas and 20 traces, five pods share each curve. Extend the list to
     * spread the load further.
     */
    private static final List<String> TRACES = List.of(
        "workload/planetlab/20110303/146-179_surfsnel_dsl_internl_net_colostate_557",
        "workload/planetlab/20110303/146-179_surfsnel_dsl_internl_net_rnp_dcc_ufjf",
        "workload/planetlab/20110303/146-179_surfsnel_dsl_internl_net_root",
        "workload/planetlab/20110303/146-179_surfsnel_dsl_internl_net_tsinghua_xyz",
        "workload/planetlab/20110303/146-179_surfsnel_dsl_internl_net_uw_oneswarm",
        "workload/planetlab/20110303/147-179_surfsnel_dsl_internl_net_tsinghua_xyz",
        "workload/planetlab/20110303/147-179_surfsnel_dsl_internl_net_uw_oneswarm",
        "workload/planetlab/20110303/75-130-96-12_static_oxfr_ma_charter_com_irisaple_wup",
        "workload/planetlab/20110303/75-130-96-12_static_oxfr_ma_charter_com_root",
        "workload/planetlab/20110303/75-130-96-12_static_oxfr_ma_charter_com_tsinghua_xyz",
        "workload/planetlab/20110303/ait05_us_es_irisaple_wup",
        "workload/planetlab/20110303/ait05_us_es_princeton_codeen",
        "workload/planetlab/20110303/ait05_us_es_root",
        "workload/planetlab/20110303/ait05_us_es_uw_oneswarm",
        "workload/planetlab/20110303/chimay_infonet_fundp_ac_be_irisaple_HEAP",
        "workload/planetlab/20110303/chimay_infonet_fundp_ac_be_tsinghua_xyz",
        "workload/planetlab/20110303/chimay_infonet_fundp_ac_be_tum_i2p",
        "workload/planetlab/20110303/chimay_infonet_fundp_ac_be_uw_oneswarm",
        "workload/planetlab/20110303/chronos_disy_inf_uni-konstanz_de_nyu_d",
        "workload/planetlab/20110303/chronos_disy_inf_uni-konstanz_de_root"
    );

    /** Result record for tests. */
    public record Summary(int nodeCount, int podCount,
                          double avgDemand, double peakNodeUtil,
                          int sampleCount,
                          double simEndClock, long wallClockMs) {}

    public static void main(String[] args) {
        new K8sPlanetLabExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        return run();
    }

    private Summary run() {
        final int nodeCount = intProp("k8s.nodes", 30);
        final int replicas = intProp("k8s.replicas", 100);
        final double duration = doubleProp("k8s.duration", 3600.0);
        final double tickInterval = doubleProp("k8s.tickInterval", 1.0);
        final int schedulingInterval = intProp("k8s.schedulingInterval", 300);
        final long containerLengthMI = longProp("k8s.containerLengthMI", 50_000_000L);
        final double cpuMultiplier = doubleProp("k8s.cpuMultiplier", 4.5);

        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s PlanetLab Example  —  Trace-driven heterogeneity   ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : Drive container CPU usage from real PlanetLab traces and");
        log("         observe scheduler behaviour under heterogeneous load.");
        log("  Tests: large-scale workload parses + schedules without crash;");
        log("         per-sample demand vs. allocated CPU vs. node hotspot.");
        log("  Knobs: nodes=%d  replicas=%d  duration=%.0fs  tickInterval=%.2fs",
            nodeCount, replicas, duration, tickInterval);
        log("         schedulingInterval=%ds  traces=%d  cpuMultiplier=%.2f",
            schedulingInterval, TRACES.size(), cpuMultiplier);
        log("");

        final var sim = new CloudSimPlus();

        final var nodes = new ArrayList<KubernetesNode>(nodeCount);
        for (int i = 1; i <= nodeCount; i++) {
            nodes.add(NodeBuilder.of("worker-" + i)
                .pes(4, 1000).ram(8_192).rack("r" + i).build());
        }

        final var dc = new DatacenterSimple(sim, nodes,
            new KubernetesScheduler(VmAllocationPolicyTopologyAware.Policy.COST_OPTIMIZED));
        // PlanetLab traces are sampled every 300s; without matching the DC interval,
        // cloudlet CPU is held flat between simulation events and the curve disappears.
        dc.setSchedulingInterval(schedulingInterval);

        final var broker = new KubernetesClusterBroker(sim)
            .setControllerTickIntervalSeconds(tickInterval);

        // PlanetLab values are read in [0,1]; mapper scales then clips so we don't
        // synthesise impossible utilisations >100%. With multiplier=1.0 this is identity.
        final java.util.function.UnaryOperator<Double> cpuMapper =
            v -> Math.min(1.0, v * cpuMultiplier);

        final var template = new PodTemplate(ord -> {
            final var tracePath = TRACES.get(ord % TRACES.size());
            final var container = ContainerBuilder.of("nginx")
                .image("nginx:1.21")
                .cpu("500m").mem("256Mi")
                .length(containerLengthMI)
                .build();
            // (path, mapper) factory uses DEF_SCHEDULING_INTERVAL=300; override it
            // explicitly so a user-supplied -Dk8s.schedulingInterval is honoured.
            final var um = UtilizationModelPlanetLab.getInstance(tracePath, cpuMapper);
            um.setSchedulingInterval(schedulingInterval);
            container.setUtilizationModelCpu(um);
            return PodBuilder.of("web-" + ord)
                .label("app", "web")
                .container(container)
                .build();
        });

        final var deployment = new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "web",
            Namespace.DEFAULT,
            template,
            replicas
        ).setStrategy(UpdateStrategy.RollingUpdate.defaults());
        broker.addController(deployment);

        final AtomicReference<List<Placement>> snapshot = new AtomicReference<>(List.of());
        final var loadStats = new LoadStats();

        broker.registerTick((Tick) clock -> {
            // Capture placement once pods are scheduled (broker destroys them on terminate).
            if (snapshot.get().isEmpty() && clock >= 5.0) {
                final var current = new ArrayList<Placement>();
                for (KubernetesNode node : broker.getNodes()) {
                    for (KubernetesPod pod : broker.placedPodsOnNode(node)) {
                        current.add(new Placement(
                            pod.getPodName(),
                            pod.getNamespace().getName(),
                            node.getNodeName(),
                            node.getRackId()));
                    }
                }
                snapshot.set(current);
            }
            // Per-tick sampling at the trace step (300s). Captures:
            //   - per-pod demand list (for avg / p95)
            //   - per-pod allocated list (for sanity vs. demand)
            //   - per-node CPU pressure (sum of pod-PE-shares ÷ node PEs), which is
            //     what the scheduler's bin-packing actually controls.
            if (clock - loadStats.lastSampledAt >= schedulingInterval) {
                loadStats.lastSampledAt = clock;
                final var demands = new ArrayList<Double>();
                double sumAllocated = 0;
                int nAllocated = 0;
                for (KubernetesPod pod : broker.getPods()) {
                    for (KubernetesContainer c : pod.getContainers()) {
                        demands.add(c.getUtilizationOfCpu(clock));
                    }
                    if (pod.isCreated()) {
                        sumAllocated += pod.getCpuPercentUtilization();
                        nAllocated++;
                    }
                }
                double maxNodeUtil = 0;
                int activeNodes = 0;
                for (KubernetesNode node : broker.getNodes()) {
                    final long nodePes = node.getPesNumber();
                    if (nodePes == 0) continue;
                    double fractionalPesUsed = 0;
                    boolean nodeActive = false;
                    for (KubernetesPod pod : broker.placedPodsOnNode(node)) {
                        nodeActive = true;
                        for (KubernetesContainer c : pod.getContainers()) {
                            fractionalPesUsed +=
                                c.getUtilizationOfCpu(clock) * c.getPesNumber();
                        }
                    }
                    if (nodeActive) activeNodes++;
                    final double nodeUtil = fractionalPesUsed / nodePes;
                    if (nodeUtil > maxNodeUtil) maxNodeUtil = nodeUtil;
                }
                if (!demands.isEmpty()) {
                    final double avgDemand = demands.stream()
                        .mapToDouble(Double::doubleValue).average().orElse(0);
                    final double p95Demand = percentile(demands, 0.95);
                    final double maxDemand = demands.stream()
                        .mapToDouble(Double::doubleValue).max().orElse(0);
                    final double avgAllocated = nAllocated > 0 ? sumAllocated / nAllocated : 0;
                    loadStats.timeline.add(new Sample(
                        clock, avgDemand, p95Demand, maxDemand,
                        avgAllocated, maxNodeUtil, nAllocated, activeNodes));
                }
            }
        });

        sim.terminateAt(duration);

        final long startNs = System.nanoTime();
        sim.start();
        final long wallClockMs = (System.nanoTime() - startNs) / 1_000_000L;

        printSummary(nodes.size(), broker.getPods().size(), snapshot.get(),
            sim.clock(), wallClockMs, loadStats);

        final var ts = loadStats.timeline;
        final double avgDemand = ts.isEmpty() ? 0.0
            : ts.stream().mapToDouble(s -> s.avgDemand).average().orElse(0.0);
        final double peakNodeUtil = ts.isEmpty() ? 0.0
            : ts.stream().mapToDouble(s -> s.maxNodeUtil).max().orElse(0.0);
        return new Summary(nodes.size(), broker.getPods().size(),
            avgDemand, peakNodeUtil, ts.size(), sim.clock(), wallClockMs);
    }

    private static int intProp(String key, int defaultValue) {
        final var raw = System.getProperty(key);
        return raw == null ? defaultValue : Integer.parseInt(raw);
    }

    private static long longProp(String key, long defaultValue) {
        final var raw = System.getProperty(key);
        return raw == null ? defaultValue : Long.parseLong(raw);
    }

    private static double doubleProp(String key, double defaultValue) {
        final var raw = System.getProperty(key);
        return raw == null ? defaultValue : Double.parseDouble(raw);
    }

    private static void printSummary(int nodeCount, int podCount, List<Placement> placements,
                                     double clock, long wallClockMs, LoadStats stats) {
        log("  Workload aggregates:");
        log("  ─────────────────────────────────────────────────────────");
        log("  nodes=%d  pods=%d  simEndClock=%.2fs  wallClockMs=%d",
            nodeCount, podCount, clock, wallClockMs);
        if (!stats.timeline.isEmpty()) {
            final var ts = stats.timeline;
            final double avgDemand = ts.stream().mapToDouble(s -> s.avgDemand).average().orElse(0);
            final double peakDemand = ts.stream().mapToDouble(s -> s.maxDemand).max().orElse(0);
            final double avgAllocated = ts.stream().mapToDouble(s -> s.avgAllocated).average().orElse(0);
            final double avgMaxNode = ts.stream().mapToDouble(s -> s.maxNodeUtil).average().orElse(0);
            final double peakMaxNode = ts.stream().mapToDouble(s -> s.maxNodeUtil).max().orElse(0);
            log("  traceDemand    : avg=%5.1f%%  peakSinglePod=%5.1f%%",
                100 * avgDemand, 100 * peakDemand);
            log("  vmAllocatedCpu : avg=%5.1f%%  (sanity check vs. traceDemand)",
                100 * avgAllocated);
            log("  nodeHotspot    : avg=%5.1f%%  peak=%5.1f%%  (max-loaded node per sample)",
                100 * avgMaxNode, 100 * peakMaxNode);
            log("  samples=%d  readyPodsAtLastSample=%d  activeNodesAtLastSample=%d",
                ts.size(), ts.get(ts.size() - 1).readyPods, ts.get(ts.size() - 1).activeNodes);

            log("");
            log("  Per-sample timeline (CSV; copy into a notebook for plotting):");
            log("  t,avgDemand,p95Demand,maxDemand,avgAllocated,maxNodeUtil,readyPods,activeNodes");
            for (Sample s : ts) {
                log("  %.0f,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%d",
                    s.t, s.avgDemand, s.p95Demand, s.maxDemand,
                    s.avgAllocated, s.maxNodeUtil, s.readyPods, s.activeNodes);
            }
        } else {
            log("  CPU stats: n/a (no samples — duration < schedulingInterval?)");
        }
        log("");
        log("  Pod → node placement (mid-run snapshot):");
        log("  %-12s %-10s %-12s %s", "POD", "NAMESPACE", "NODE", "RACK");
        log("  ─────────────────────────────────────────────────────────");
        final int rowLimit = 25;
        int shown = 0;
        for (Placement p : placements) {
            if (shown++ >= rowLimit) {
                log("  ... and %d more pods", placements.size() - rowLimit);
                break;
            }
            log("  %-12s %-10s %-12s %s", p.pod, p.namespace, p.node, p.rack);
        }
        log("  ─────────────────────────────────────────────────────────");
        log("");

        if (podCount > 0 && !stats.timeline.isEmpty()) {
            log("✅ VALIDATION PASSED: scheduled %d pods and collected %d trace samples.",
                podCount, stats.timeline.size());
        } else {
            log("❌ VALIDATION FAILED: pods=%d  samples=%d (expected both > 0).",
                podCount, stats.timeline.size());
        }
    }

    private static void log(String fmt, Object... args) {
        if (args.length == 0) System.out.println(fmt);
        else System.out.printf(fmt + "%n", args);
    }

    /** Simple linear-interpolation percentile; values list is mutated (sorted in place). */
    private static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) return 0;
        final var sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        final double idx = p * (sorted.size() - 1);
        final int lo = (int) Math.floor(idx);
        final int hi = (int) Math.ceil(idx);
        if (lo == hi) return sorted.get(lo);
        return sorted.get(lo) + (idx - lo) * (sorted.get(hi) - sorted.get(lo));
    }

    private record Placement(String pod, String namespace, String node, String rack) {}

    private record Sample(double t, double avgDemand, double p95Demand, double maxDemand,
                          double avgAllocated, double maxNodeUtil,
                          int readyPods, int activeNodes) {}

    private static final class LoadStats {
        double lastSampledAt = -1.0;
        final List<Sample> timeline = new ArrayList<>();
    }
}
