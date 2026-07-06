package org.cloudsimplus.examples.kubernetes;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.DeploymentController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.vms.Vm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates how to inject a custom scheduling heuristic by subclassing
 * {@link KubernetesScheduler} and overriding {@link #score(Vm, Host)}.
 *
 * <p>The custom heuristic here is <b>bin-packing</b>: among feasible nodes,
 * prefer the one that already has the LEAST free MIPS (i.e. the most-loaded
 * node). This packs pods tightly onto a few hosts, keeping the rest empty so
 * a Cluster Autoscaler can decommission them. It is the opposite of the
 * default {@code COST_OPTIMIZED} policy, which spreads to the cheapest nodes.
 *
 * <p>How it works in upstream:
 * <ol>
 *   <li>Strict filters (nodeSelector, taints, NodeAffinity required, schedulable
 *       flag, PodAffinity required) still run via the parent's
 *       {@code passesStrictConstraints}.</li>
 *   <li>{@code score()} is overridden — lower score wins. We return the host's
 *       <i>free MIPS</i>: the lower the free capacity, the more attractive.</li>
 *   <li>A deterministic lexical tie-break is added on top so two equally-loaded
 *       nodes still pick the same winner across JVMs.</li>
 * </ol>
 *
 * <p>Knobs (JVM system properties):
 * <pre>
 *   -Dk8s.nodes=4       worker nodes (4 PE × 1000 MIPS, 8 GiB each)
 *   -Dk8s.replicas=8    Deployment replica count
 *   -Dk8s.duration=60   terminateAt() in simulated seconds
 * </pre>
 */
public class K8sCustomSchedulerExample {

    public record Summary(int nodeCount, int replicas,
                          int placedPodCount, int hostsUsed,
                          double simEndClock, long wallClockMs) {}

    /** Snapshot of pod-per-node placement at the last tick before termination. */
    private final Map<String, List<String>> lastPlacement = new HashMap<>();

    public static void main(String[] args) {
        new K8sCustomSchedulerExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        suppressSimLogs();

        final int nodeCount = intProp("k8s.nodes", 4);
        final int replicas  = intProp("k8s.replicas", 8);
        final double dur    = doubleProp("k8s.duration", 60.0);

        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s Custom Scheduler  —  Bin-packing heuristic         ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : Inject a custom bin-packing scheduling heuristic.");
        log("  Tests: pods pack onto the fewest feasible nodes (score = free MIPS, lower wins).");
        log("  Knobs: nodes=%d  replicas=%d  duration=%.0fs", nodeCount, replicas, dur);
        log("");

        final var sim = new CloudSimPlus();

        final var nodes = new ArrayList<KubernetesNode>(nodeCount);
        for (int i = 1; i <= nodeCount; i++) {
            nodes.add(NodeBuilder.of("worker-" + i)
                .pes(4, 1000).ram(8_192).rack("r" + i).build());
        }

        // Custom scheduler in place of the stock KubernetesScheduler. The parent
        // policy is COST_OPTIMIZED only because the enum requires a value; the
        // overridden score(...) ignores the parent's score entirely.
        final var scheduler = new BinPackingScheduler();
        new DatacenterSimple(sim, nodes, scheduler);

        final var broker = new KubernetesClusterBroker(sim)
            .setControllerTickIntervalSeconds(1.0);

        final var template = new PodTemplate(ord -> PodBuilder.of("web-" + ord)
            .label("app", "web")
            .container(ContainerBuilder.of("nginx")
                .image("nginx:1.21")
                .cpu("500m").mem("256Mi")
                .length(50_000)
                .build())
            .build());

        final var deployment = new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "web", Namespace.DEFAULT, template, replicas);
        broker.addController(deployment);

        // Snapshot placement on every tick so we have a non-empty view to print
        // after terminateAt clears the broker's pod list.
        broker.registerTick((Tick) clock -> {
            final var snap = new HashMap<String, List<String>>();
            int placed = 0;
            for (KubernetesNode n : nodes) {
                final var pods = broker.placedPodsOnNode(n).stream()
                    .map(KubernetesPod::getPodName)
                    .toList();
                snap.put(n.getNodeName(), pods);
                placed += pods.size();
            }
            if (placed > 0) {
                lastPlacement.clear();
                lastPlacement.putAll(snap);
            }
        });

        sim.terminateAt(dur);
        final long t0 = System.nanoTime();
        sim.start();
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;

        log("  Pod → node placement (final tick before termination):");
        log("  ─────────────────────────────────────────────────────────");
        log("  %-12s %s", "NODE", "PODS");
        log("  ─────────────────────────────────────────────────────────");
        int hostsUsed = 0;
        int placedTotal = 0;
        for (KubernetesNode node : nodes) {
            final var podsHere = lastPlacement.getOrDefault(node.getNodeName(), List.of());
            final String summary = podsHere.isEmpty() ? "(empty)" : String.join(", ", podsHere);
            log("  %-12s %s", node.getNodeName(), summary);
            if (!podsHere.isEmpty()) {
                hostsUsed++;
                placedTotal += podsHere.size();
            }
        }
        log("  ─────────────────────────────────────────────────────────");
        log("  Pods placed         : %d / %d", placedTotal, replicas);
        log("  Hosts used          : %d / %d  (bin-packing keeps this low)",
            hostsUsed, nodeCount);
        log("  Sim clock : %.2f s   wall: %d ms", sim.clock(), wallMs);
        log("");
        
        // Explicit Validation
        if (placedTotal == replicas && hostsUsed < nodes.size()) {
            log("✅ VALIDATION PASSED: All " + replicas + " replicas scheduled, tightly packed on " + hostsUsed + " nodes (less than the " + nodes.size() + " total nodes).");
        } else {
            log("❌ VALIDATION FAILED: Expected tighter bin-packing on fewer nodes, but used " + hostsUsed + " out of " + nodes.size() + " nodes, or didn't place all pods.");
        }

        return new Summary(nodes.size(), replicas, placedTotal, hostsUsed,
            sim.clock(), wallMs);
    }

    /**
     * Custom scheduling heuristic: score = free MIPS on the host. Lower wins,
     * so the most-loaded feasible node attracts the next pod. The parent's
     * strict filters still run via {@code super.passesStrictConstraints} (we
     * don't override it).
     */
    public static final class BinPackingScheduler extends KubernetesScheduler {

        public BinPackingScheduler() {
            super(VmAllocationPolicyTopologyAware.Policy.COST_OPTIMIZED);
        }

        @Override
        protected double score(final Vm vm, final Host host) {
            final double reserved = host.getVmList().stream()
                .mapToDouble(Vm::getTotalMipsCapacity)
                .sum();
            final double free = host.getTotalMipsCapacity() - reserved;
            return free + lexicalRankBoost(host);
        }

        /**
         * Adds the parent's lexical-tie-break magnitude so two hosts with equal
         * free-MIPS still pick a deterministic winner across JVMs. Bounded by
         * {@code maxRank * TIE_BREAK_EPSILON} which stays well below 1µ.
         */
        private double lexicalRankBoost(final Host host) {
            final String myName = host instanceof KubernetesNode kn
                ? kn.effectiveName() : Long.toString(host.getId());
            int rank = 0;
            for (final Host h : getHostList()) {
                final String n = h instanceof KubernetesNode kn ? kn.effectiveName()
                    : Long.toString(h.getId());
                if (n.compareTo(myName) < 0) rank++;
            }
            return rank * TIE_BREAK_EPSILON;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void suppressSimLogs() {
        try {
            ((ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("org.cloudsimplus"))
                .setLevel(ch.qos.logback.classic.Level.WARN);
        } catch (ClassCastException ignored) {}
    }

    private static void log(String fmt, Object... args) {
        if (args.length == 0) System.out.println(fmt);
        else System.out.printf(fmt + "%n", args);
    }

    private static int intProp(String key, int def) {
        final var v = System.getProperty(key);
        return v == null ? def : Integer.parseInt(v);
    }

    private static double doubleProp(String key, double def) {
        final var v = System.getProperty(key);
        return v == null ? def : Double.parseDouble(v);
    }
}
