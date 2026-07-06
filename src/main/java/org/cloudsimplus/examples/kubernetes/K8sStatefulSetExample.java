package org.cloudsimplus.examples.kubernetes;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.controllers.StatefulSetController;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Demonstrates StatefulSetController: pods receive stable, ordinal names
 * ({@code db-0}, {@code db-1}, {@code db-2}) that survive restarts and
 * scale-out events in a predictable order.
 *
 * <p>Sequence of events:
 * <ol>
 *   <li>t = 0s  — StatefulSet created; 3 replicas requested.</li>
 *   <li>t ≥ 5s  — Snapshot initial placement (db-0..2).</li>
 *   <li>t = 30s — Scale up to 5; two new pods db-3, db-4 are added
 *                 in ordinal order.</li>
 *   <li>t = end — Final placement table printed.</li>
 * </ol>
 *
 * Knobs (JVM system properties):
 *   -Dk8s.nodes=3       worker nodes (8 PE × 1000 MIPS, 16 GiB each)
 *   -Dk8s.replicas=3    initial StatefulSet replica count
 *   -Dk8s.duration=90   terminateAt() in simulated seconds
 */
public class K8sStatefulSetExample {

    public record Summary(int initialReplicaCount, int finalReplicaCount,
                          List<String> initialPodNames,
                          List<String> finalPodNames,
                          double simEndClock, long wallClockMs) {}

    public static void main(String[] args) {
        new K8sStatefulSetExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        suppressSimLogs();

        final int nodeCount = intProp("k8s.nodes", 3);
        final int replicas  = intProp("k8s.replicas", 3);
        final double dur    = doubleProp("k8s.duration", 90.0);

        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s StatefulSet Example  —  Stable pod identity        ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : Show StatefulSetController preserves ordinal pod identity across scaling.");
        log("  Tests: scale-up adds db-N+1, db-N+2 (no random hashes).");
        log("  Knobs: nodes=%d  initialReplicas=%d  scaleUpAt=30s  duration=%.0fs",
            nodeCount, replicas, dur);
        log("");

        final var sim = new CloudSimPlus();

        final var nodes = new ArrayList<KubernetesNode>(nodeCount);
        for (int i = 1; i <= nodeCount; i++) {
            nodes.add(NodeBuilder.of("worker-" + i)
                .pes(8, 1000).ram(16_384).rack("r" + i).build());
        }

        new DatacenterSimple(sim, nodes,
            new KubernetesScheduler(VmAllocationPolicyTopologyAware.Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim)
            .setControllerTickIntervalSeconds(1.0);

        final var template = new PodTemplate(ord -> PodBuilder.of("db-" + ord)
            .label("app", "postgres")
            .label("role", "database")
            .container(ContainerBuilder.of("postgres")
                .image("postgres:15")
                .cpu("1000m").mem("2048Mi")
                .length(500_000) // long-running stateful workload
                .build())
            .build());

        final var sts = new StatefulSetController(
            broker.getControllerManager().allocateUid(),
            "db",
            Namespace.DEFAULT,
            template,
            replicas
        );
        broker.addController(sts);

        // ── tick: snapshot initial state, trigger scale-up, snapshot final ──
        final AtomicBoolean initialSnapped = new AtomicBoolean(false);
        final AtomicBoolean scaled         = new AtomicBoolean(false);
        final AtomicBoolean finalSnapped   = new AtomicBoolean(false);

        final AtomicReference<List<String>> initialPods = new AtomicReference<>(List.of());
        final AtomicReference<List<String>> finalPods   = new AtomicReference<>(List.of());
        final List<String> events = new ArrayList<>();

        broker.registerTick((Tick) clock -> {
            if (!initialSnapped.get() && clock >= 5.0) {
                initialSnapped.set(true);
                initialPods.set(podNames(sts));
                events.add(String.format("  t=%5.1fs  INITIAL   replicas=%d  pods=%s",
                    clock, sts.getManagedPods().size(), initialPods.get()));
            }
            if (!scaled.get() && clock >= 30.0) {
                scaled.set(true);
                final int newR = replicas + 2;
                sts.setDesiredReplicas(newR);
                events.add(String.format("  t=%5.1fs  SCALE-UP  desired %d → %d",
                    clock, replicas, newR));
            }
            if (!finalSnapped.get() && clock >= dur - 5.0) {
                finalSnapped.set(true);
                finalPods.set(podNames(sts));
                events.add(String.format("  t=%5.1fs  FINAL     replicas=%d  pods=%s",
                    clock, sts.getManagedPods().size(), finalPods.get()));
            }
        });

        sim.terminateAt(dur);
        final long t0 = System.nanoTime();
        sim.start();
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;

        log("  Lifecycle events:");
        log("  ─────────────────────────────────────────────────────────────");
        events.forEach(System.out::println);
        log("");
        log("  Final pod → node placement:");
        log("  %-10s %-10s %-12s %s", "POD", "NAMESPACE", "NODE", "RACK");
        log("  ─────────────────────────────────────────────────────────────");
        for (KubernetesNode node : broker.getNodes()) {
            for (KubernetesPod pod : broker.placedPodsOnNode(node)) {
                log("  %-10s %-10s %-12s %s",
                    pod.getPodName(),
                    pod.getNamespace().getName(),
                    node.getNodeName(),
                    node.getRackId());
            }
        }
        log("  ─────────────────────────────────────────────────────────────");
        log("  Sim clock : %.2f s   wall: %d ms", sim.clock(), wallMs);
        log("");
        
        // Explicit Validation
        int expectedFinal = replicas + 2;
        if (finalPods.get().size() == expectedFinal && finalPods.get().contains("db-" + (expectedFinal - 1))) {
            log("✅ VALIDATION PASSED: StatefulSet correctly scaled to " + expectedFinal + " pods with strict ordinal identities.");
        } else {
            log("❌ VALIDATION FAILED: Expected " + expectedFinal + " ordinal pods, but found " + finalPods.get().size() + ". Pods: " + finalPods.get());
        }
        return new Summary(replicas, finalPods.get().size(),
            initialPods.get(), finalPods.get(), sim.clock(), wallMs);
    }

    private static List<String> podNames(StatefulSetController sts) {
        return sts.getManagedPods().stream()
            .map(KubernetesPod::getPodName)
            .sorted()
            .toList();
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

    private static int intProp(String key, int def) {
        final var v = System.getProperty(key);
        return v == null ? def : Integer.parseInt(v);
    }

    private static double doubleProp(String key, double def) {
        final var v = System.getProperty(key);
        return v == null ? def : Double.parseDouble(v);
    }
}
