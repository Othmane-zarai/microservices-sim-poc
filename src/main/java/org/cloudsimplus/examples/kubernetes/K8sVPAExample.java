package org.cloudsimplus.examples.kubernetes;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.autoscaling.VerticalPodAutoscaler;
import org.cloudsimplus.kubernetes.autoscaling.VerticalPodAutoscaler.Mode;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.controllers.ReplicaSetController;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;

import java.util.ArrayList;
import java.util.List;

/**
 * Demonstrates {@link VerticalPodAutoscaler}: while the
 * {@link ReplicaSetController}'s pods sustain 90 % CPU, the VPA's 70 % target
 * drives a recommendation that scales the per-pod CPU request upward
 * (since {@code newMilliCpu = round(currentMilliCpu × avgCpu / target)}).
 *
 * <p>The recommendation is exposed through
 * {@link VerticalPodAutoscaler#getRecommendedMilliCpu()} but pods are
 * <i>not</i> mutated automatically — that mirrors upstream K8s VPA "Off"
 * mode. {@code -Dk8s.vpa.evict=true} flips on auto-eviction so the
 * ReplicaSet recreates pods, which a template-aware author can use to roll
 * out the recommendation.</p>
 *
 * <p>Knobs (JVM system properties):</p>
 * <pre>
 *   -Dk8s.nodes=4              worker nodes (8 PE × 1000 MIPS, 16 GiB)
 *   -Dk8s.replicas=3           ReplicaSet replica count
 *   -Dk8s.vpa.targetCpu=0.7    VPA target avg CPU utilisation (in (0, 1])
 *   -Dk8s.vpa.cooldown=15      simulated seconds between VPA actions
 *   -Dk8s.vpa.evict=false      auto-evict on each new recommendation
 *   -Dk8s.duration=120         terminateAt() in simulated seconds
 * </pre>
 */
public class K8sVPAExample {

    /** Initial CPU request applied to every container. */
    private static final String INITIAL_CPU_REQUEST = "500m";

    public record Summary(int initialMilliCpu, long recommendedMilliCpu,
                          long finalContainerMilliCpu,
                          double targetCpu, int recommendationUpdates,
                          Mode mode, double simEndClock, long wallClockMs) {}

    public static void main(String[] args) {
        new K8sVPAExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        suppressSimLogs();

        final int    nodeCount  = intProp("k8s.nodes", 4);
        final int    replicas   = intProp("k8s.replicas", 3);
        final double targetCpu  = doubleProp("k8s.vpa.targetCpu", 0.7);
        final double load       = doubleProp("k8s.vpa.load", 0.90);
        final double cooldown   = doubleProp("k8s.vpa.cooldown", 15.0);
        final boolean evict     = boolProp("k8s.vpa.evict", false);
        final double duration   = doubleProp("k8s.duration", 120.0);
        final Mode   vpaMode    = Mode.valueOf(
            System.getProperty("k8s.vpa.mode", "INITIAL").toUpperCase());

        final int initialMilliCpu = 500; // matches INITIAL_CPU_REQUEST
        final int expected = (int) Math.round(initialMilliCpu * load / targetCpu);

        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s VPA Example  —  Vertical Pod Autoscaling           ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : Show VPA recommend a larger CPU request when pods run hot.");
        log("  Modes: INITIAL (recommend only, default) | AUTO (in-place resize)");
        log("  Knobs: nodes=%d  replicas=%d  initialCpu=%dm  load=%.0f%%  vpaTarget=%.0f%%",
            nodeCount, replicas, initialMilliCpu, load * 100, targetCpu * 100);
        log("         cooldown=%.0fs  mode=%s  evict=%s  duration=%.0fs",
            cooldown, vpaMode, evict, duration);
        log("  Formula (per K8s VPA): newCpu = round(currentCpu × avgCpu / target)");
        log("                       = round(%dm × %.2f / %.2f) ≈ %dm",
            initialMilliCpu, load, targetCpu, expected);
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

        final var template = new PodTemplate(ord -> PodBuilder.of("api-" + ord)
            .label("app", "api")
            .container(ContainerBuilder.of("api")
                .image("api:1.0")
                .cpu(INITIAL_CPU_REQUEST).mem("512Mi")
                .length(1_000_000) // long-running so VPA has time to sample
                .cpuUtilization(new UtilizationModelDynamic(load))
                .build())
            .build());

        final var rs = new ReplicaSetController(
            broker.getControllerManager().allocateUid(),
            "api",
            Namespace.DEFAULT,
            template,
            replicas);
        broker.addController(rs);

        final var vpa = new VerticalPodAutoscaler("api-vpa", rs)
            .setMode(vpaMode)
            .setTargetCpuUtilization(targetCpu)
            .setCooldownSeconds(cooldown)
            .setEvictOnRecommendation(evict);
        broker.registerTick(vpa);

        // ── Timeline: log every recommendation or effective-limits change ─────
        final List<String> timeline = new ArrayList<>();
        final long[] lastRec = {0};
        final long[] maxEffectiveCpu = {initialMilliCpu};
        final int[] updates = {0};
        broker.registerTick((Tick) clock -> {
            final long rec = vpa.getRecommendedMilliCpu();
            if (rec != lastRec[0]) {
                lastRec[0] = rec;
                updates[0]++;
                final long effCpu = rs.getManagedPods().stream()
                    .flatMap(p -> p.getContainers().stream())
                    .mapToLong(c -> c.getEffectiveLimits().milliCpu())
                    .max().orElse(initialMilliCpu);
                maxEffectiveCpu[0] = Math.max(maxEffectiveCpu[0], effCpu);
                final String modeTag = (vpaMode == Mode.AUTO)
                    ? String.format("  effectiveCpu=%4dm", effCpu) : "";
                timeline.add(String.format(
                    "  t=%5.1fs │ UPDATE #%-2d │ recommendCpu=%4dm%s",
                    clock, updates[0], rec, modeTag));
            }
        });

        sim.terminateAt(duration);
        final long t0 = System.nanoTime();
        sim.start();
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;

        log("  VPA %s timeline:", vpaMode == Mode.AUTO ? "in-place resize" : "recommendation");
        log("  ─────────────────────────────────────────────────────────");
        if (timeline.isEmpty()) {
            log("  (no update — load within tolerance, or cooldown not elapsed)");
        } else {
            timeline.forEach(System.out::println);
        }
        log("  ─────────────────────────────────────────────────────────");
        log("  Initial CPU request : %dm", initialMilliCpu);
        log("  Final recommend CPU : %dm  (target %.0f%%)",
            vpa.getRecommendedMilliCpu(), targetCpu * 100);
        if (vpaMode == Mode.AUTO) {
            log("  Max effective CPU   : %dm  (in-place, no pod eviction)", maxEffectiveCpu[0]);
        }
        log("  Recommend RAM       : %d MiB", vpa.getRecommendedMemMiB());
        log("  Updates issued      : %d", updates[0]);
        log("  Mode                : %s", vpaMode);
        log("  Sim clock           : %.2f s   wall: %d ms", sim.clock(), wallMs);
        log("");

        final boolean ok = vpa.getRecommendedMilliCpu() > initialMilliCpu;
        final String verdict = ok
            ? (vpaMode == Mode.AUTO
                ? "✅ AUTO mode: in-place resize cpu %dm → %dm (no pod eviction)"
                : "✅ INITIAL/OFF mode: VPA recommended cpu %dm → %dm")
            : "❌ FAILED: VPA did not raise the CPU recommendation above %dm (got %dm)";
        log(verdict, initialMilliCpu,
            ok ? vpa.getRecommendedMilliCpu() : vpa.getRecommendedMilliCpu());
        return new Summary(initialMilliCpu, vpa.getRecommendedMilliCpu(),
            maxEffectiveCpu[0], targetCpu, updates[0], vpaMode, sim.clock(), wallMs);
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

    private static boolean boolProp(String key, boolean def) {
        final var v = System.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }
}
