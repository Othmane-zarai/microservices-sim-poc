package org.cloudsimplus.examples.kubernetes;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.controllers.ReplicaSetController;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Demonstrates {@link ReplicaSetController} used directly (without a wrapping
 * {@code DeploymentController}). The example scales the replica count manually
 * mid-run via {@link ReplicaSetController#setDesiredReplicas(int)} so the user
 * can see the controller reconcile up and down on its own.
 *
 * <p>Sequence of events:</p>
 * <ol>
 *   <li>t = 0s   — ReplicaSet created with {@code initial} replicas.</li>
 *   <li>t = 20s  — Manual scale-up to {@code initial + 2}.</li>
 *   <li>t = 50s  — Manual scale-down to {@code initial - 1}.</li>
 *   <li>t = end  — Final placement and replica count printed.</li>
 * </ol>
 *
 * <p>Knobs (JVM system properties):</p>
 * <pre>
 *   -Dk8s.nodes=4         worker nodes (4 PE × 1000 MIPS, 8 GiB)
 *   -Dk8s.replicas=3      initial ReplicaSet replica count
 *   -Dk8s.duration=80     terminateAt() in simulated seconds
 * </pre>
 */
public class K8sReplicaSetExample {

    public record Summary(int initialReplicas, int afterScaleUp, int afterScaleDown,
                          int finalReplicas,
                          double simEndClock, long wallClockMs) {}

    public static void main(String[] args) {
        new K8sReplicaSetExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        suppressSimLogs();

        final int    nodeCount = intProp("k8s.nodes", 4);
        final int    initial   = intProp("k8s.replicas", 3);
        final double duration  = doubleProp("k8s.duration", 80.0);
        final int    upCount   = initial + 2;
        final int    downCount = Math.max(1, initial - 1);

        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s ReplicaSet Example  —  Direct controller usage     ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : Drive a standalone ReplicaSetController through scale-up + scale-down.");
        log("  Tests: setDesiredReplicas reconciles managed pods to the new target.");
        log("  Knobs: nodes=%d  initial=%d  scaleUp@20s→%d  scaleDown@50s→%d  duration=%.0fs",
            nodeCount, initial, upCount, downCount, duration);
        log("");

        final var sim = new CloudSimPlus();

        final var nodes = new ArrayList<KubernetesNode>(nodeCount);
        for (int i = 1; i <= nodeCount; i++) {
            nodes.add(NodeBuilder.of("worker-" + i)
                .pes(4, 1000).ram(8_192).rack("r" + i).build());
        }

        new DatacenterSimple(sim, nodes,
            new KubernetesScheduler(VmAllocationPolicyTopologyAware.Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim)
            .setControllerTickIntervalSeconds(1.0);

        final var template = new PodTemplate(ord -> PodBuilder.of("cache-" + ord)
            .label("app", "cache")
            .container(ContainerBuilder.of("redis")
                .image("redis:7.2")
                .cpu("500m").mem("512Mi")
                .length(1_000_000) // long-running
                .build())
            .build());

        final var rs = new ReplicaSetController(
            broker.getControllerManager().allocateUid(),
            "cache",
            Namespace.DEFAULT,
            template,
            initial);
        broker.addController(rs);

        // ── Mid-run lifecycle: scale up at 20s, scale down at 50s ─────────────
        final AtomicBoolean scaledUp   = new AtomicBoolean(false);
        final AtomicBoolean scaledDown = new AtomicBoolean(false);
        final int[] sizeAfterUp   = {-1};
        final int[] sizeAfterDown = {-1};

        final List<String> events = new ArrayList<>();
        // terminateAt() destroys all pods on shutdown, so finalSize[0]
        // reads 0 after sim.start() returns. Capture the last live value via tick.
        final int[] finalSize = {0};

        broker.registerTick((Tick) clock -> {
            if (!scaledUp.get() && clock >= 20.0) {
                scaledUp.set(true);
                events.add(String.format(
                    "  t=%5.1fs  SCALE-UP    desired %d → %d  (current=%d)",
                    clock, initial, upCount, rs.currentReplicas()));
                rs.setDesiredReplicas(upCount);
            }
            if (scaledUp.get() && sizeAfterUp[0] < 0 && clock >= 35.0) {
                sizeAfterUp[0] = rs.currentReplicas();
                events.add(String.format(
                    "  t=%5.1fs  SETTLED-UP  managedPods=%d  (target=%d)",
                    clock, sizeAfterUp[0], upCount));
            }
            if (!scaledDown.get() && clock >= 50.0) {
                scaledDown.set(true);
                events.add(String.format(
                    "  t=%5.1fs  SCALE-DOWN  desired %d → %d  (current=%d)",
                    clock, upCount, downCount, rs.currentReplicas()));
                rs.setDesiredReplicas(downCount);
            }
            if (scaledDown.get() && sizeAfterDown[0] < 0 && clock >= 65.0) {
                sizeAfterDown[0] = rs.currentReplicas();
                events.add(String.format(
                    "  t=%5.1fs  SETTLED-DN  managedPods=%d  (target=%d)",
                    clock, sizeAfterDown[0], downCount));
            }
            // Track the last in-sim observation so the post-shutdown report
            // reflects what was running rather than the post-destroy zero.
            finalSize[0] = rs.currentReplicas();
        });

        sim.terminateAt(duration);
        final long t0 = System.nanoTime();
        sim.start();
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;

        log("  Lifecycle events:");
        log("  ─────────────────────────────────────────────────────────");
        events.forEach(System.out::println);
        log("  ─────────────────────────────────────────────────────────");
        log("  Initial replicas    : %d", initial);
        log("  After scale-up      : %d  (target %d)",
            sizeAfterUp[0] < 0 ? finalSize[0] : sizeAfterUp[0], upCount);
        log("  After scale-down    : %d  (target %d)",
            sizeAfterDown[0] < 0 ? finalSize[0] : sizeAfterDown[0], downCount);
        log("  Final managed pods  : %d", finalSize[0]);
        log("  Sim clock           : %.2f s   wall: %d ms", sim.clock(), wallMs);
        log("");

        final boolean upOk   = sizeAfterUp[0]   == upCount;
        final boolean downOk = sizeAfterDown[0] == downCount;
        if (upOk && downOk) {
            log("✅ VALIDATION PASSED: ReplicaSet reconciled to %d then %d as requested.",
                upCount, downCount);
        } else {
            log("❌ VALIDATION FAILED: scale-up settled=%d (expected %d), scale-down settled=%d (expected %d).",
                sizeAfterUp[0], upCount, sizeAfterDown[0], downCount);
        }
        return new Summary(initial, sizeAfterUp[0], sizeAfterDown[0],
            finalSize[0], sim.clock(), wallMs);
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
