package org.cloudsimplus.examples.kubernetes;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.autoscaling.HorizontalPodAutoscaler;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.DeploymentController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.controllers.UpdateStrategy;
import org.cloudsimplus.kubernetes.lifecycle.PodPhase;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;

import java.util.ArrayList;
import java.util.List;

/**
 * Demonstrates HorizontalPodAutoscaler: a Deployment starts with
 * {@code initReplicas} pods each sustaining 80 % CPU. Because the HPA target
 * is 50 %, the formula {@code desired = ceil(current × avgCpu / target)}
 * drives replica count upward each cooldown interval until {@code maxReplicas}
 * is reached.
 *
 * <p>The scaling timeline is printed every 5 simulated seconds so the
 * progression is visible without CloudSimPlus simulation noise.
 *
 * Knobs (JVM system properties):
 *   -Dk8s.nodes=4           worker nodes (8 PE × 1000 MIPS, 32 GiB each)
 *   -Dk8s.replicas=2        initial Deployment replica count
 *   -Dk8s.hpa.target=0.5   HPA target average CPU utilization (fraction 0–1)
 *   -Dk8s.hpa.min=1         HPA minimum replicas
 *   -Dk8s.hpa.max=8         HPA maximum replicas
 *   -Dk8s.hpa.cooldown=10   HPA cooldown between scale events (simulated seconds)
 *   -Dk8s.duration=120      terminateAt() in simulated seconds
 */
public class K8sHPAExample {

    public record Summary(int initReplicas, int finalRunningReplicas,
                          int totalPodsSubmitted, int hpaMin, int hpaMax,
                          int desiredReplicas,
                          double simEndClock, long wallClockMs) {}

    public static void main(String[] args) {
        new K8sHPAExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        suppressSimLogs();

        final int nodeCount    = intProp("k8s.nodes", 4);
        final int initReplicas = intProp("k8s.replicas", 2);
        final double target    = doubleProp("k8s.hpa.target", 0.5);
        final int hpaMin       = intProp("k8s.hpa.min", 1);
        final int hpaMax       = intProp("k8s.hpa.max", 8);
        final double cooldown  = doubleProp("k8s.hpa.cooldown", 10.0);
        final double dur       = doubleProp("k8s.duration", 120.0);

        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s HPA Example  —  Horizontal Pod Autoscaling         ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : Show HPA scaling a Deployment up under sustained load.");
        log("  Tests: HPA computes desired replicas via ceil(current × avgCpu/target)");
        log("         and scales until maxReplicas (or load drops).");
        log("  Knobs: nodes=%d  initReplicas=%d  cpuLoad=80%%  hpaTarget=%.0f%%",
            nodeCount, initReplicas, target * 100);
        log("         min=%d  max=%d  cooldown=%.0fs  duration=%.0fs",
            hpaMin, hpaMax, cooldown, dur);
        log("  Hint : at 2 pods × 80%% CPU and target=%.0f%% → desired = %d",
            target * 100, (int) Math.ceil(2 * 0.80 / target));
        log("");

        final var sim = new CloudSimPlus();

        final var nodes = new ArrayList<KubernetesNode>(nodeCount);
        for (int i = 1; i <= nodeCount; i++) {
            nodes.add(NodeBuilder.of("worker-" + i)
                .pes(8, 1000).ram(32_768).rack("r" + i).build());
        }

        new DatacenterSimple(sim, nodes,
            new KubernetesScheduler(VmAllocationPolicyTopologyAware.Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim)
            .setControllerTickIntervalSeconds(1.0);

        final var template = new PodTemplate(ord -> PodBuilder.of("api-" + ord)
            .label("app", "api-server")
            .container(ContainerBuilder.of("api")
                .image("api-server:2.0")
                .cpu("500m").mem("512Mi")
                .length(500_000) // long-running so pods survive the whole sim
                .cpuUtilization(new UtilizationModelDynamic(0.8))
                .build())
            .build());

        final var deployment = new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "api-server",
            Namespace.DEFAULT,
            template,
            initReplicas
        ).setStrategy(UpdateStrategy.RollingUpdate.defaults());
        broker.addController(deployment);

        final var hpa = HorizontalPodAutoscaler.of(deployment, target)
            .setMinReplicas(hpaMin)
            .setMaxReplicas(hpaMax)
            .setCooldownSeconds(cooldown);
        broker.registerTick(hpa);

        // ── timeline: sample running replica count every 5 simulated seconds ──
        final List<String> timeline = new ArrayList<>();
        int[] lastSample = {-1};
        int[] lastCount  = {0};
        broker.registerTick((Tick) clock -> {
            final int sec = (int) clock;
            if (sec > 0 && sec % 5 == 0 && sec != lastSample[0]) {
                lastSample[0] = sec;
                final int running = (int) broker.getPods().stream()
                    .filter(p -> p.getPhase() == PodPhase.RUNNING
                              && p.getLabels().has("app")
                              && "api-server".equals(p.getLabels().get("app")))
                    .count();
                final String scaleFlag = running > lastCount[0] ? "  ↑ SCALE-UP"
                    : running < lastCount[0] ? "  ↓ SCALE-DOWN" : "";
                final String bar = "▓".repeat(running) + "░".repeat(Math.max(0, hpaMax - running));
                timeline.add(String.format(
                    "  t=%5ds │ replicas=%-2d │ [%-8s] (max=%d)%s",
                    sec, running, bar, hpaMax, scaleFlag));
                lastCount[0] = running;
            }
        });

        sim.terminateAt(dur);
        final long t0 = System.nanoTime();
        sim.start();
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;

        log("  HPA scaling timeline (sampled every 5s):");
        log("  ─────────────────────────────────────────────────────────");
        timeline.forEach(System.out::println);
        log("  ─────────────────────────────────────────────────────────");

        final int finalCount = (int) broker.getPods().stream()
            .filter(p -> p.getLabels().has("app")
                      && "api-server".equals(p.getLabels().get("app")))
            .count();
        log("  Total pods submitted : %d", broker.getPods().size());
        log("  Final replica count  : %d  (max configured: %d)", finalCount, hpaMax);
        log("  Sim clock : %.2f s   wall: %d ms", sim.clock(), wallMs);
        log("");
        
        // Explicit Validation
        if (finalCount > initReplicas) {
            log("✅ VALIDATION PASSED: HPA successfully scaled up from " + initReplicas + " to " + finalCount + " replicas due to sustained high load.");
        } else {
            log("❌ VALIDATION FAILED: HPA failed to scale up from the initial " + initReplicas + " replicas despite the CPU load.");
        }
        return new Summary(initReplicas, finalCount, broker.getPods().size(),
            hpaMin, hpaMax, deployment.getDesiredReplicas(), sim.clock(), wallMs);
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
