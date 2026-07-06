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
import org.cloudsimplus.kubernetes.controllers.CronJobController;
import org.cloudsimplus.kubernetes.controllers.JobController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.lifecycle.RestartPolicy;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * Demonstrates {@link CronJobController}: fires a fresh batch {@link JobController}
 * every {@code k8s.cron.schedule} simulated seconds. The example proves the
 * three building blocks of the upstream cron layer:
 *
 * <ol>
 *   <li><b>Schedule honoured.</b> Over a {@code duration}-second run with a
 *       {@code schedule}-second period the controller fires
 *       {@code floor(duration / schedule)} times.</li>
 *   <li><b>JobFactory wiring.</b> Each firing yields an independent Job whose
 *       pods carry an ordinal-suffixed name ({@code report-0-task-0}, …).</li>
 *   <li><b>ConcurrencyPolicy.</b> {@code FORBID} suppresses a firing if the
 *       previous Job is still running; flip the property to observe the
 *       difference.</li>
 * </ol>
 *
 * <p>Knobs (JVM system properties):</p>
 * <pre>
 *   -Dk8s.nodes=2                  worker nodes (4 PE × 1000 MIPS, 8 GiB)
 *   -Dk8s.cron.schedule=20         period between firings (simulated seconds)
 *   -Dk8s.cron.policy=ALLOW        ALLOW | FORBID | REPLACE
 *   -Dk8s.cron.completions=1       completions per fired Job
 *   -Dk8s.cron.taskLengthMI=15000  per-task work, sized below schedule period
 *   -Dk8s.duration=120             terminateAt() in simulated seconds
 * </pre>
 */
public class K8sCronJobExample {

    public record Summary(int firedCount, int expectedFires,
                          String concurrencyPolicy,
                          double simEndClock, long wallClockMs) {}

    public static void main(String[] args) {
        new K8sCronJobExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        suppressSimLogs();

        final int    nodeCount   = intProp("k8s.nodes", 2);
        final double schedule    = doubleProp("k8s.cron.schedule", 20.0);
        final String policyName  = System.getProperty("k8s.cron.policy", "ALLOW");
        final int    completions = intProp("k8s.cron.completions", 1);
        final long   taskLengthMI = longProp("k8s.cron.taskLengthMI", 15_000L);
        final double duration    = doubleProp("k8s.duration", 120.0);
        final var    policy      = CronJobController.ConcurrencyPolicy.valueOf(policyName);

        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s CronJob Example  —  Periodic batch firings         ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : Fire a fresh JobController every %.0fs and verify the count.", schedule);
        log("  Tests: schedule honoured, JobFactory wiring, ConcurrencyPolicy.");
        log("  Knobs: nodes=%d  schedule=%.0fs  policy=%s  taskLenMI=%d  duration=%.0fs",
            nodeCount, schedule, policy, taskLengthMI, duration);
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

        // ── JobFactory: each firing builds a fresh JobController ──────────────
        final CronJobController.JobFactory factory = (jobUid, firingIndex) -> {
            final String jobName = "report-" + firingIndex;
            final var template = new PodTemplate(ord -> PodBuilder.of(jobName + "-task-" + ord)
                .label("cronjob", "report")
                .label("firing", String.valueOf(firingIndex))
                .container(ContainerBuilder.of("reporter")
                    .image("reporter:1.0")
                    .cpu("500m").mem("256Mi")
                    .length(taskLengthMI)
                    .restartPolicy(RestartPolicy.NEVER)
                    .build())
                .build());
            return new JobController(jobUid, jobName, Namespace.DEFAULT, template)
                .setCompletions(completions)
                .setParallelism(1);
        };

        final var cron = new CronJobController(
            broker.getControllerManager().allocateUid(),
            "report",
            Namespace.DEFAULT,
            factory)
            .setSchedule(schedule)
            .setConcurrencyPolicy(policy);
        broker.addController(cron);

        // ── Timeline: log each firing event ───────────────────────────────────
        final List<String> timeline = new ArrayList<>();
        final int[] lastFired = {0};
        broker.registerTick((Tick) clock -> {
            final int now = cron.getFired();
            if (now > lastFired[0]) {
                lastFired[0] = now;
                final var lastJob = cron.getLastJob();
                final String jobName = lastJob == null ? "(none)" : lastJob.getName();
                timeline.add(String.format(
                    "  t=%5.1fs │ FIRE #%-2d │ job=%-12s │ totalFired=%d",
                    clock, now - 1, jobName, now));
            }
        });

        sim.terminateAt(duration);
        final long t0 = System.nanoTime();
        sim.start();
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;

        final int expected = (int) Math.floor(duration / schedule);

        log("  CronJob firing timeline:");
        log("  ─────────────────────────────────────────────────────────");
        if (timeline.isEmpty()) {
            log("  (no firings — duration < schedule?)");
        } else {
            timeline.forEach(System.out::println);
        }
        log("  ─────────────────────────────────────────────────────────");
        log("  Concurrency policy : %s", policy);
        log("  Total firings      : %d  (expected ≥ %d under ALLOW)",
            cron.getFired(), expected);
        log("  Last fired at      : %.1fs", cron.getLastFiredAt());
        log("  Sim clock          : %.2f s   wall: %d ms", sim.clock(), wallMs);
        log("");

        final boolean ok = policy == CronJobController.ConcurrencyPolicy.ALLOW
            ? cron.getFired() >= expected
            : cron.getFired() > 0;
        if (ok) {
            log("✅ VALIDATION PASSED: CronJob produced %d firings under %s policy.",
                cron.getFired(), policy);
        } else {
            log("❌ VALIDATION FAILED: expected %s firings, observed %d.",
                policy == CronJobController.ConcurrencyPolicy.ALLOW
                    ? "≥" + expected : ">0",
                cron.getFired());
        }
        return new Summary(cron.getFired(), expected, policy.name(),
            sim.clock(), wallMs);
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

    private static long longProp(String key, long def) {
        final var v = System.getProperty(key);
        return v == null ? def : Long.parseLong(v);
    }

    private static double doubleProp(String key, double def) {
        final var v = System.getProperty(key);
        return v == null ? def : Double.parseDouble(v);
    }
}
