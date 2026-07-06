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
import org.cloudsimplus.kubernetes.controllers.JobController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.lifecycle.RestartPolicy;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Kubernetes Job example: a batch job runs short-lived task pods to
 * completion. JobController enforces parallelism (max concurrent pods) and
 * completions (required successes) before marking the job done.
 *
 * <p>Expected output shows a timeline of active vs. succeeded tasks and a final
 * pass/fail verdict, with no CloudSimPlus internal noise.
 *
 * Knobs (JVM system properties):
 *   -Dk8s.nodes=3          worker nodes (4 PE × 1000 MIPS, 8 GiB each)
 *   -Dk8s.completions=5    required successful task completions
 *   -Dk8s.parallelism=2    max concurrent task pods
 *   -Dk8s.duration=120     terminateAt() in simulated seconds
 */
public class K8sJobExample {

    /** Result record for tests. */
    public record Summary(int succeeded, int failures, int completions,
                          int backoffLimit, boolean complete,
                          double simEndClock, long wallClockMs) {}

    public static void main(String[] args) {
        new K8sJobExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        suppressSimLogs();

        final int nodeCount   = intProp("k8s.nodes", 3);
        final int completions = intProp("k8s.completions", 5);
        final int parallelism = intProp("k8s.parallelism", 2);
        final double duration = doubleProp("k8s.duration", 120.0);

        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s Job Example  —  Batch tasks to completion          ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : Run short-lived batch tasks under a JobController.");
        log("  Tests: parallelism cap and required successful completions.");
        log("  Knobs: nodes=%d  completions=%d  parallelism=%d  duration=%.0fs",
            nodeCount, completions, parallelism, duration);
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

        final var template = new PodTemplate(ord -> PodBuilder.of("task-" + ord)
            .label("job-name", "batch-processor")
            .container(ContainerBuilder.of("worker")
                .image("batch-worker:1.0")
                .cpu("500m").mem("256Mi")
                .length(20_000)
                .restartPolicy(RestartPolicy.NEVER)
                .build())
            .build());

        final var job = new JobController(
            broker.getControllerManager().allocateUid(),
            "batch-processor",
            Namespace.DEFAULT,
            template
        ).setCompletions(completions).setParallelism(parallelism);

        broker.addController(job);

        final List<String> timeline = new ArrayList<>();
        int[] lastSucceeded = {0};
        broker.registerTick((Tick) clock -> {
            if (clock > 0 && clock == Math.floor(clock)) {
                final int active    = job.getActive().size();
                final int succeeded = job.getSucceeded();
                if (active != 0 || succeeded != lastSucceeded[0] || clock <= 5.0) {
                    final String bar = "█".repeat(succeeded)
                        + "░".repeat(Math.max(0, completions - succeeded));
                    timeline.add(String.format("  t=%5.0fs │ active=%-2d │ done=%-2d/%-2d │ [%s]",
                        clock, active, succeeded, completions, bar));
                    lastSucceeded[0] = succeeded;
                }
            }
        });

        sim.terminateAt(duration);
        final long t0 = System.nanoTime();
        sim.start();
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;

        log("  Job timeline (non-idle ticks):");
        log("  ─────────────────────────────────────────────────────");
        timeline.forEach(System.out::println);
        log("  ─────────────────────────────────────────────────────");
        log("  Status    : %s", job.isComplete() ? "✓ COMPLETE" : "✗ TIMED-OUT (duration hit)");
        log("  Succeeded : %d / %d", job.getSucceeded(), completions);
        log("  Failures  : %d  (backoff limit: %d)", job.getFailures(), job.getBackoffLimit());
        log("  Sim clock : %.2f s   wall: %d ms", sim.clock(), wallMs);
        log("");
        
        // Explicit Validation
        if (job.getSucceeded() == completions && job.isComplete()) {
            log("✅ VALIDATION PASSED: The Job reached the target of " + completions + " successful completions.");
        } else {
            log("❌ VALIDATION FAILED: The Job completed " + job.getSucceeded() + " out of " + completions + " target completions.");
        }
        return new Summary(job.getSucceeded(), job.getFailures(), completions,
            job.getBackoffLimit(), job.isComplete(), sim.clock(), wallMs);
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
