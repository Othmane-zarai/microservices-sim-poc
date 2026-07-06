package org.cloudsimplus.examples.kubernetes;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.DaemonSetController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Demonstrates DaemonSetController: the scheduler places exactly one
 * monitoring-agent pod on every node that carries the label
 * {@code role=monitoring}, and no pod on unlabeled nodes.
 *
 * <p>Cluster layout (4 nodes):
 * <pre>
 *   mon-node-1  (role=monitoring)  ← daemon pod placed here
 *   mon-node-2  (role=monitoring)  ← daemon pod placed here
 *   app-node-1  (no label)         ← skipped by DaemonSet
 *   app-node-2  (no label)         ← skipped by DaemonSet
 * </pre>
 *
 * Knobs (JVM system properties):
 *   -Dk8s.duration=60   terminateAt() in simulated seconds
 */
public class K8sDaemonSetExample {

    public record Summary(int totalNodes, int monitoringNodes,
                          int daemonPodsPlaced,
                          double simEndClock, long wallClockMs) {}

    public static void main(String[] args) {
        new K8sDaemonSetExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        suppressSimLogs();

        final double dur = doubleProp("k8s.duration", 60.0);

        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s DaemonSet Example  —  One agent per labeled node   ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : Place exactly one DaemonSet pod per node matching the selector.");
        log("  Tests: label-selector match (role=monitoring); skip non-matching nodes.");
        log("  Layout: 4 nodes — 2 × role=monitoring, 2 × unlabeled.");
        log("  Knobs : duration=%.0fs", dur);
        log("");

        final var sim = new CloudSimPlus();

        // Two monitoring-labeled nodes and two plain app nodes
        final var nodes = List.of(
            NodeBuilder.of("mon-node-1").pes(4, 1000).ram(8_192).rack("r1")
                .label("role", "monitoring").build(),
            NodeBuilder.of("mon-node-2").pes(4, 1000).ram(8_192).rack("r2")
                .label("role", "monitoring").build(),
            NodeBuilder.of("app-node-1").pes(4, 1000).ram(8_192).rack("r3").build(),
            NodeBuilder.of("app-node-2").pes(4, 1000).ram(8_192).rack("r4").build()
        );

        new DatacenterSimple(sim, nodes,
            new KubernetesScheduler(VmAllocationPolicyTopologyAware.Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim)
            .setControllerTickIntervalSeconds(1.0);

        final var template = new PodTemplate(ord -> PodBuilder.of("node-exporter-" + ord)
            .label("app", "node-exporter")
            .label("component", "monitoring")
            .container(ContainerBuilder.of("exporter")
                .image("prom/node-exporter:v1.6.0")
                .cpu("100m").mem("128Mi")
                .length(1_000_000) // long-running daemon
                .build())
            .build());

        final var ds = new DaemonSetController(
            broker.getControllerManager().allocateUid(),
            "node-exporter",
            Namespace.DEFAULT,
            template
        );
        ds.setNodeSelector(LabelSelector.matchLabel("role", "monitoring"));
        broker.addController(ds);

        // Capture placement once pods are scheduled
        final AtomicBoolean snapped = new AtomicBoolean(false);
        final List<String> placement = new ArrayList<>();

        broker.registerTick((Tick) clock -> {
            if (!snapped.get() && clock >= 5.0) {
                snapped.set(true);
                for (KubernetesNode node : broker.getNodes()) {
                    final List<KubernetesPod> pods = broker.placedPodsOnNode(node);
                    final String nodeLabel = node.getLabels().has("role")
                        ? "role=" + node.getLabels().get("role")
                        : "(no label)";
                    if (pods.isEmpty()) {
                        placement.add(String.format(
                            "  %-14s [%-20s]  → (skipped — no daemon pod)", node.getNodeName(), nodeLabel));
                    } else {
                        for (KubernetesPod pod : pods) {
                            placement.add(String.format(
                                "  %-14s [%-20s]  → %s", node.getNodeName(), nodeLabel, pod.getPodName()));
                        }
                    }
                }
            }
        });

        sim.terminateAt(dur);
        final long t0 = System.nanoTime();
        sim.start();
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;

        final int monitoringNodes = (int) nodes.stream()
            .filter(n -> n.getLabels().has("role")
                && "monitoring".equals(n.getLabels().get("role")))
            .count();

        log("  Node → daemon pod placement:");
        log("  ─────────────────────────────────────────────────────────────");
        log("  %-14s  %-22s  %s", "NODE", "NODE LABELS", "DAEMON POD");
        log("  ─────────────────────────────────────────────────────────────");
        placement.forEach(System.out::println);
        log("  ─────────────────────────────────────────────────────────────");
        long actualPlaced = placement.stream().filter(s -> !s.contains("(skipped")).count();
        log("  Total daemon pods placed : %d  (expected %d)", actualPlaced, monitoringNodes);
        log("  Sim clock : %.2f s   wall: %d ms", sim.clock(), wallMs);
        log("");
        
        // Explicit Validation
        if (actualPlaced == monitoringNodes) {
            log("✅ VALIDATION PASSED: Exactly " + monitoringNodes + " DaemonSet pods placed, matching the number of eligible nodes.");
        } else {
            log("❌ VALIDATION FAILED: Expected " + monitoringNodes + " DaemonSet pods, but found " + actualPlaced + ".");
        }
        return new Summary(nodes.size(), monitoringNodes,
            (int) actualPlaced, sim.clock(), wallMs);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

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

    private static double doubleProp(String key, double def) {
        final var v = System.getProperty(key);
        return v == null ? def : Double.parseDouble(v);
    }
}
