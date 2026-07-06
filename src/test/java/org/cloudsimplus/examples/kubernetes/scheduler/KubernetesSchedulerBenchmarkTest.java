package org.cloudsimplus.examples.kubernetes.scheduler;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.DeploymentController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Hand-timed performance benchmark for the Kubernetes scheduler placement loop.
 *
 * <p>Sweeps {@code (n, p) in {10, 100, 1000} x {100, 500, 1000, 5000}} — 12
 * cells total. For each cell, builds {@code n} nodes and a single Deployment
 * with {@code p} replicas, runs the simulator just long enough for the
 * scheduler to attempt placement on every pod, and records wall-time,
 * pods/second throughput, and the JVM heap delta. Warmup is one untimed run
 * per cell; the reported numbers are the median of three timed runs.
 *
 * <p>This test is tagged {@code "benchmark"} and Surefire excludes the
 * {@code benchmark} group via {@code <excludedGroups>}, so {@code mvn test}
 * does NOT run it. To execute, run {@code mvn test -Dgroups=benchmark}.
 *
 * <p>The "JMH" in colloquial naming is metaphorical — there is no JMH
 * framework dependency; we hand-time with {@link System#nanoTime()} as
 * decided in §A.1 (Decision A1: {@code @Tag("benchmark")}, soft CI gate).
 *
 * <p>Outputs:
 * <ul>
 *   <li>A markdown summary table to stdout.</li>
 *   <li>One JSON record per cell to
 *       {@code docs/profiling/jmh-baseline.json}.</li>
 * </ul>
 *
 * <p>Soft gate: this test never fails on wall-time. It is purely a recorder.
 */
@Tag("benchmark")
class KubernetesSchedulerBenchmarkTest {

    /** Node counts to sweep. */
    private static final int[] NODE_COUNTS = {10, 100, 1000};

    /** Pod counts to sweep. */
    private static final int[] POD_COUNTS = {100, 500, 1000, 5000};

    /** Untimed warmup runs per cell (before measurement). */
    private static final int WARMUP_RUNS = 1;

    /** Timed runs per cell; median is reported. */
    private static final int TIMED_RUNS = 3;

    /**
     * Short simulated duration — we only need the controller to tick once so
     * the scheduler attempts placement on every pod. The wall-time spent
     * inside {@code sim.start()} is dominated by that placement pass, which
     * is what we want to measure.
     */
    private static final double SIM_DURATION_SECONDS = 5.0;

    /** Output path for the JSON baseline. Resolved relative to project root. */
    private static final Path BASELINE_JSON =
        Paths.get("docs", "profiling", "jmh-baseline.json");

    @Test
    void runSchedulerSweep() throws IOException {
        suppressSimLogs();

        final List<CellResult> results = new ArrayList<>(NODE_COUNTS.length * POD_COUNTS.length);

        System.out.println();
        System.out.println("## Kubernetes Scheduler Benchmark Sweep");
        System.out.println("Warmup runs per cell: " + WARMUP_RUNS
            + ", timed runs per cell: " + TIMED_RUNS
            + ", sim duration: " + SIM_DURATION_SECONDS + "s");
        System.out.println();

        for (final int n : NODE_COUNTS) {
            for (final int p : POD_COUNTS) {
                final CellResult r = runCell(n, p);
                results.add(r);
                System.out.printf(Locale.ROOT,
                    "  cell n=%-4d p=%-4d  walltime=%6d ms  pods/s=%8.1f  heapDelta=%+5d MiB%n",
                    r.n, r.p, r.walltimeMs, r.podsPerSec, r.heapDeltaMB);
            }
        }

        printMarkdownTable(results);
        writeJsonBaseline(results);

        // Soft gate: this benchmark is a recorder, never a gate. The current
        // baseline at (n=10, p=1000) is ~4.7 s. We deliberately do NOT
        // assert on wall-time so CI never fails on perf drift (per A1
        // decision: report only).
    }

    // ---------------------------------------------------------------------
    // Per-cell execution
    // ---------------------------------------------------------------------

    private CellResult runCell(final int n, final int p) {
        // Warmup
        for (int i = 0; i < WARMUP_RUNS; i++) {
            runOnce(n, p);
        }

        // Force GC before measurement so heap delta is meaningful
        final Runtime rt = Runtime.getRuntime();
        System.gc();
        sleepQuietly(50);
        System.gc();
        sleepQuietly(50);
        final long heapBeforeBytes = rt.totalMemory() - rt.freeMemory();

        final long[] times = new long[TIMED_RUNS];
        long peakHeapDeltaBytes = Long.MIN_VALUE;
        for (int i = 0; i < TIMED_RUNS; i++) {
            final long heapStart = rt.totalMemory() - rt.freeMemory();
            final long t0 = System.nanoTime();
            runOnce(n, p);
            times[i] = System.nanoTime() - t0;
            final long heapEnd = rt.totalMemory() - rt.freeMemory();
            peakHeapDeltaBytes = Math.max(peakHeapDeltaBytes, heapEnd - heapStart);
        }

        Arrays.sort(times);
        final long medianNs = times[times.length / 2];
        final long walltimeMs = medianNs / 1_000_000L;

        final double podsPerSec = walltimeMs > 0
            ? (p * 1000.0) / walltimeMs
            : Double.POSITIVE_INFINITY;

        // Force GC again so the reported delta reflects retained, not transient, allocation
        System.gc();
        sleepQuietly(50);
        final long heapAfterBytes = rt.totalMemory() - rt.freeMemory();
        final long retainedDeltaBytes = heapAfterBytes - heapBeforeBytes;
        // Prefer the larger of (peak transient delta, retained delta) so the
        // number captures both retention and the working-set high-water mark.
        final long reportedDeltaBytes = Math.max(retainedDeltaBytes, peakHeapDeltaBytes);
        final long heapDeltaMB = reportedDeltaBytes / (1024L * 1024L);

        return new CellResult(n, p, walltimeMs, podsPerSec, heapDeltaMB);
    }

    /**
     * Builds an n-node cluster + a single Deployment with p replicas, then
     * runs the simulator long enough for the scheduler to attempt placement.
     * Uses the same builder API exercised by {@code K8sClusterExample} and
     * {@code K8sCustomSchedulerExample}.
     */
    private void runOnce(final int n, final int p) {
        final var sim = new CloudSimPlus();

        final List<KubernetesNode> nodes = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            nodes.add(NodeBuilder.of("worker-" + i)
                .pes(4, 1000)
                .ram(8_192)
                .rack("r" + ((i % 16) + 1))
                .build());
        }

        new DatacenterSimple(sim, nodes,
            new KubernetesScheduler(
                VmAllocationPolicyTopologyAware.Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim)
            .setControllerTickIntervalSeconds(1.0);

        final var template = new PodTemplate(ord -> PodBuilder.of("bench-" + ord)
            .label("app", "bench")
            .container(ContainerBuilder.of("nginx")
                .image("nginx:1.21")
                .cpu("100m").mem("64Mi")
                .length(50_000)
                .build())
            .build());

        final var deployment = new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "bench",
            Namespace.DEFAULT,
            template,
            p);
        broker.addController(deployment);

        sim.terminateAt(SIM_DURATION_SECONDS);
        // Silence sim stdout for the duration of the run — the scheduler
        // can print thousands of pod-placement INFO lines per cell.
        final java.io.PrintStream realOut = System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        try {
            sim.start();
        } finally {
            System.setOut(realOut);
        }
    }

    // ---------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------

    private void printMarkdownTable(final List<CellResult> results) {
        System.out.println();
        System.out.println("### Results (median of " + TIMED_RUNS + " runs)");
        System.out.println();
        System.out.println("| nodes | pods | wall-time (ms) | pods/sec | heap delta (MiB) |");
        System.out.println("|------:|-----:|---------------:|---------:|-----------------:|");
        for (final CellResult r : results) {
            System.out.printf(Locale.ROOT,
                "| %d | %d | %d | %.1f | %d |%n",
                r.n, r.p, r.walltimeMs, r.podsPerSec, r.heapDeltaMB);
        }
        System.out.println();
        System.out.println("Reference baseline (n=10, p=1000): ~4700 ms — not gated.");
        System.out.println();
    }

    private void writeJsonBaseline(final List<CellResult> results) throws IOException {
        Files.createDirectories(BASELINE_JSON.getParent());
        final StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < results.size(); i++) {
            final CellResult r = results.get(i);
            sb.append("  {\"n\": ").append(r.n)
              .append(", \"p\": ").append(r.p)
              .append(", \"walltimeMs\": ").append(r.walltimeMs)
              .append(", \"podsPerSec\": ").append(String.format(Locale.ROOT, "%.1f", r.podsPerSec))
              .append(", \"heapDeltaMB\": ").append(r.heapDeltaMB)
              .append("}");
            if (i < results.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n");
        Files.writeString(BASELINE_JSON, sb.toString());
        System.out.println("Wrote baseline JSON to " + BASELINE_JSON.toAbsolutePath());
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static void suppressSimLogs() {
        try {
            ((ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("org.cloudsimplus"))
                .setLevel(ch.qos.logback.classic.Level.WARN);
        } catch (ClassCastException ignored) {
            // SLF4J binding isn't logback in this runtime — skip silently.
        }
    }

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Single cell result row. */
    private record CellResult(int n, int p, long walltimeMs, double podsPerSec, long heapDeltaMB) {}

    @SuppressWarnings("unused")
    private static List<CellResult> immutable(final List<CellResult> in) {
        return Collections.unmodifiableList(in);
    }
}
