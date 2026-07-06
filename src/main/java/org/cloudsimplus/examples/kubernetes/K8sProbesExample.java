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
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.controllers.ReplicaSetController;
import org.cloudsimplus.kubernetes.lifecycle.LivenessProbe;
import org.cloudsimplus.kubernetes.lifecycle.PodCondition;
import org.cloudsimplus.kubernetes.lifecycle.ReadinessProbe;
import org.cloudsimplus.kubernetes.lifecycle.RestartPolicy;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Demonstrates {@link ReadinessProbe} and {@link LivenessProbe}: every pod runs
 * the same workload, but the kubelet only flips its
 * {@link PodCondition#READY READY} condition once the readiness probe's
 * {@code successThreshold} is met. A second pod stream uses an always-failing
 * liveness probe with {@link RestartPolicy#ALWAYS} to exercise the container
 * restart path.
 *
 * <p>Probe semantics demonstrated:</p>
 * <ul>
 *   <li><b>Readiness — slow start.</b> The probe predicate returns false for
 *       the first {@code k8s.probes.readinessFailures} evaluations, then true.
 *       At {@code period=2s} and {@code successThreshold=1}, this delays
 *       {@code READY} by ≈ {@code (failures + 1) × 2s}.</li>
 *   <li><b>Liveness — restart loop.</b> An always-false predicate triggers
 *       a restart every {@code period × failureThreshold} simulated seconds.
 *       Set {@code -Dk8s.probes.unhealthyReplicas=0} to disable.</li>
 * </ul>
 *
 * <p>Knobs (JVM system properties):</p>
 * <pre>
 *   -Dk8s.nodes=2                          worker nodes
 *   -Dk8s.probes.healthyReplicas=2         pods with the slow-start readiness probe
 *   -Dk8s.probes.unhealthyReplicas=1       pods with the always-failing liveness probe
 *   -Dk8s.probes.readinessFailures=2       failed evaluations before readiness flips true
 *   -Dk8s.probes.period=2.0                seconds between probe evaluations
 *   -Dk8s.duration=60                      terminateAt() in simulated seconds
 * </pre>
 */
public class K8sProbesExample {

    public record Summary(int healthyReplicas, int readyAtEnd,
                          int unhealthyReplicas, int livenessRestarts,
                          double simEndClock, long wallClockMs) {}

    public static void main(String[] args) {
        new K8sProbesExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        suppressSimLogs();

        final int    nodeCount   = intProp("k8s.nodes", 2);
        final int    healthy     = intProp("k8s.probes.healthyReplicas", 2);
        final int    unhealthy   = intProp("k8s.probes.unhealthyReplicas", 1);
        final int    initFails   = intProp("k8s.probes.readinessFailures", 2);
        final double period      = doubleProp("k8s.probes.period", 2.0);
        final double duration    = doubleProp("k8s.duration", 60.0);

        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s Probes Example  —  Readiness + Liveness            ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : Show probe-driven READY transitions and liveness restarts.");
        log("  Tests: Readiness flips after N successes; failing liveness restarts.");
        log("  Knobs: nodes=%d  healthy=%d  unhealthy=%d  initFails=%d  period=%.1fs  duration=%.0fs",
            nodeCount, healthy, unhealthy, initFails, period, duration);
        log("  Estimated READY time : ≈ %.1fs after pod start (initFails+1 × period).",
            (initFails + 1) * period);
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

        // ── Healthy pods: readiness probe is false for the first N evaluations,
        //    then permanently true. Per-container counter map keeps state separate.
        final Map<KubernetesContainer, Integer> readinessHits = new HashMap<>();
        final var healthyTemplate = new PodTemplate(ord -> PodBuilder.of("web-" + ord)
            .label("app", "web")
            .container(ContainerBuilder.of("nginx")
                .image("nginx:1.25")
                .cpu("500m").mem("256Mi")
                .length(1_000_000)
                .readinessProbe(configureReadiness(
                    new ReadinessProbe(c -> {
                        final int n = readinessHits.merge(c, 1, Integer::sum);
                        return n > initFails;
                    }), period))
                .build())
            .build());

        final var healthyRs = new ReplicaSetController(
            broker.getControllerManager().allocateUid(),
            "web", Namespace.DEFAULT, healthyTemplate, healthy);
        broker.addController(healthyRs);

        // ── Unhealthy pods: liveness probe always fails, container restarts.
        final int[] livenessRestarts = {0};
        final var unhealthyTemplate = new PodTemplate(ord -> PodBuilder.of("flaky-" + ord)
            .label("app", "flaky")
            .container(ContainerBuilder.of("flaky")
                .image("flaky-app:0.1")
                .cpu("250m").mem("128Mi")
                .length(2_000_000)
                .restartPolicy(RestartPolicy.ALWAYS)
                .livenessProbe(configureLiveness(
                    new LivenessProbe(c -> {
                        livenessRestarts[0]++;
                        return false; // always unhealthy
                    }), period)) // restart on first failure
                .build())
            .build());

        if (unhealthy > 0) {
            final var flakyRs = new ReplicaSetController(
                broker.getControllerManager().allocateUid(),
                "flaky", Namespace.DEFAULT, unhealthyTemplate, unhealthy);
            broker.addController(flakyRs);
        }

        // ── Timeline: log readiness transitions for every healthy pod.
        //    Track which 'web' pods *ever* became Ready — the post-shutdown
        //    snapshot can't distinguish "never Ready" from "destroyed by
        //    terminateAt" since both leave isReady() == false.
        final List<String> timeline = new ArrayList<>();
        final Map<String, Boolean> lastReady = new HashMap<>();
        final Set<String> webEverReady = new HashSet<>();
        broker.registerTick((Tick) clock -> {
            for (KubernetesPod pod : broker.getPods()) {
                final boolean ready = pod.isReady();
                final Boolean prev = lastReady.get(pod.getPodName());
                if (prev == null || prev != ready) {
                    timeline.add(String.format(
                        "  t=%5.1fs │ pod=%-10s │ READY=%s",
                        clock, pod.getPodName(), ready ? "true " : "false"));
                    lastReady.put(pod.getPodName(), ready);
                }
                if (ready && pod.getLabels().has("app")
                    && "web".equals(pod.getLabels().get("app"))) {
                    webEverReady.add(pod.getPodName());
                }
            }
        });

        sim.terminateAt(duration);
        final long t0 = System.nanoTime();
        sim.start();
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;

        final int readyAtEnd = webEverReady.size();

        log("  Probe / readiness timeline (showing transitions only):");
        log("  ─────────────────────────────────────────────────────────");
        timeline.forEach(System.out::println);
        log("  ─────────────────────────────────────────────────────────");
        log("  Healthy 'web' pods Ready : %d / %d", readyAtEnd, healthy);
        log("  Liveness probe failures  : %d  (each triggers a restart)", livenessRestarts[0]);
        log("  Sim clock                : %.2f s   wall: %d ms", sim.clock(), wallMs);
        log("");

        final boolean readyOk = readyAtEnd == healthy;
        final boolean restartsOk = unhealthy == 0 || livenessRestarts[0] >= unhealthy;
        if (readyOk && restartsOk) {
            log("✅ VALIDATION PASSED: %d/%d healthy pods became Ready; %d liveness failures observed.",
                readyAtEnd, healthy, livenessRestarts[0]);
        } else {
            log("❌ VALIDATION FAILED: ready=%d/%d; livenessRestarts=%d (expected ≥ %d).",
                readyAtEnd, healthy, livenessRestarts[0], unhealthy);
        }
        return new Summary(healthy, readyAtEnd, unhealthy, livenessRestarts[0],
            sim.clock(), wallMs);
    }

    /** Apply common probe knobs while keeping the concrete subtype for the builder. */
    private static ReadinessProbe configureReadiness(final ReadinessProbe p, final double period) {
        p.setPeriodSeconds(period);
        p.setFailureThreshold(1);
        p.setSuccessThreshold(1);
        return p;
    }

    private static LivenessProbe configureLiveness(final LivenessProbe p, final double period) {
        p.setPeriodSeconds(period);
        p.setFailureThreshold(1);
        return p;
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
