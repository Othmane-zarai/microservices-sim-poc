package org.cloudsimplus.examples.kubernetes;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.NodeAffinity;
import org.cloudsimplus.kubernetes.PodAffinity;
import org.cloudsimplus.kubernetes.Taint;
import org.cloudsimplus.kubernetes.Toleration;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.DeploymentController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Demonstrates the K8s scheduler's placement constraints in one run:
 *
 * <ol>
 *   <li><b>Node affinity (required + tolerations).</b> Two GPU nodes carry
 *       the label {@code gpu=true} and a {@code dedicated=gpu:NO_SCHEDULE}
 *       taint. The {@code ml} Deployment requires {@code gpu In [true]} and
 *       tolerates the dedicated taint, so its pods land only on GPU nodes.</li>
 *   <li><b>Pod anti-affinity by hostname.</b> The {@code web} Deployment
 *       requires anti-affinity on its own {@code app=web} label by
 *       {@link PodAffinity.TopologyKey#HOSTNAME HOSTNAME}, so each replica
 *       lands on a distinct node.</li>
 *   <li><b>Preferred node affinity.</b> The {@code batch} Deployment prefers
 *       {@code workload=cpu} (weight 50). Without that hint, the
 *       cost-optimised parent score might steer pods anywhere; with the
 *       preference, scheduling concentrates on the CPU rack.</li>
 * </ol>
 *
 * <p>Layout (4 nodes):</p>
 * <pre>
 *   cpu-1      labels=workload:cpu zone:z1                (no taint)
 *   cpu-2      labels=workload:cpu zone:z1                (no taint)
 *   gpu-1      labels=gpu:true zone:z2 workload:gpu       taint dedicated=gpu:NO_SCHEDULE
 *   gpu-2      labels=gpu:true zone:z3 workload:gpu       taint dedicated=gpu:NO_SCHEDULE
 * </pre>
 *
 * <p>Knobs (JVM system properties):</p>
 * <pre>
 *   -Dk8s.replicas.ml=2     ml Deployment replicas (will land on GPU nodes)
 *   -Dk8s.replicas.web=3    web Deployment replicas (anti-affinity by host)
 *   -Dk8s.replicas.batch=2  batch Deployment replicas (preferred CPU rack)
 *   -Dk8s.duration=60       terminateAt() in simulated seconds
 * </pre>
 */
public class K8sAffinityExample {

    public record Summary(int mlOnGpu, int mlTotal,
                          int webDistinctNodes, int webTotal,
                          int batchOnCpu, int batchTotal,
                          double simEndClock, long wallClockMs) {}

    public static void main(String[] args) {
        new K8sAffinityExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        suppressSimLogs();

        final int mlReplicas    = intProp("k8s.replicas.ml", 2);
        final int webReplicas   = intProp("k8s.replicas.web", 3);
        final int batchReplicas = intProp("k8s.replicas.batch", 2);
        final double duration   = doubleProp("k8s.duration", 60.0);

        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s Affinity Example  —  Affinity, Taints, Tolerations ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : Show that NodeAffinity, PodAffinity, and Tolerations all");
        log("         steer placement on a heterogeneous cluster.");
        log("  Tests: ml pods land on GPU nodes only; web pods spread by hostname;");
        log("         batch pods prefer the CPU rack.");
        log("  Knobs: ml=%d  web=%d  batch=%d  duration=%.0fs",
            mlReplicas, webReplicas, batchReplicas, duration);
        log("");

        final var sim = new CloudSimPlus();

        // ── Heterogeneous nodes with labels and taints ────────────────────────
        final Taint gpuDedicated = new Taint("dedicated", "gpu", Taint.Effect.NO_SCHEDULE);
        final var nodes = List.of(
            NodeBuilder.of("cpu-1").pes(8, 1000).ram(16_384)
                .rack("r-cpu").zone("z1")
                .label("workload", "cpu").build(),
            NodeBuilder.of("cpu-2").pes(8, 1000).ram(16_384)
                .rack("r-cpu").zone("z1")
                .label("workload", "cpu").build(),
            NodeBuilder.of("gpu-1").pes(8, 1000).ram(16_384)
                .rack("r-gpu").zone("z2")
                .label("workload", "gpu").label("gpu", "true")
                .taint(gpuDedicated).build(),
            NodeBuilder.of("gpu-2").pes(8, 1000).ram(16_384)
                .rack("r-gpu").zone("z3")
                .label("workload", "gpu").label("gpu", "true")
                .taint(gpuDedicated).build()
        );

        new DatacenterSimple(sim, nodes,
            new KubernetesScheduler(VmAllocationPolicyTopologyAware.Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim)
            .setControllerTickIntervalSeconds(1.0);

        // ── ml Deployment: requires gpu=true and tolerates the GPU taint ──────
        final var mlAffinity = NodeAffinity.builder()
            .require(LabelSelector.builder().matchIn("gpu", "true").build())
            .build();
        final var mlToleration = Toleration.equal("dedicated", "gpu", Taint.Effect.NO_SCHEDULE);

        final var mlTemplate = new PodTemplate(ord -> {
            final var pod = PodBuilder.of("ml-" + ord)
                .label("app", "ml")
                .nodeAffinity(mlAffinity)
                .tolerate(mlToleration)
                .container(ContainerBuilder.of("trainer")
                    .image("trainer:1.0")
                    .cpu("2000m").mem("4096Mi")
                    .length(500_000)
                    .build())
                .build();
            return pod;
        });
        broker.addController(new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "ml", Namespace.DEFAULT, mlTemplate, mlReplicas));

        // ── web Deployment: pod anti-affinity by hostname (one per node).
        //    Tolerates the GPU taint so all 4 nodes are eligible — without
        //    the toleration the GPU nodes would filter out and we'd cap at 2.
        final var webAntiAffinity = PodAffinity.builder()
            .requireAntiAffinity(
                LabelSelector.matchLabel("app", "web"),
                PodAffinity.TopologyKey.HOSTNAME)
            .build();
        final var webTemplate = new PodTemplate(ord -> {
            final var pod = PodBuilder.of("web-" + ord)
                .label("app", "web")
                .tolerate(mlToleration)
                .container(ContainerBuilder.of("nginx")
                    .image("nginx:1.25")
                    .cpu("500m").mem("256Mi")
                    .length(500_000)
                    .build())
                .build();
            pod.setPodAffinity(webAntiAffinity);
            return pod;
        });
        broker.addController(new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "web", Namespace.DEFAULT, webTemplate, webReplicas));

        // ── batch Deployment: prefers workload=cpu nodes ──────────────────────
        final var batchAffinity = NodeAffinity.builder()
            .prefer(LabelSelector.builder().matchIn("workload", "cpu").build(), 50)
            .build();
        final var batchTemplate = new PodTemplate(ord -> PodBuilder.of("batch-" + ord)
            .label("app", "batch")
            .nodeAffinity(batchAffinity)
            .container(ContainerBuilder.of("worker")
                .image("worker:1.0")
                .cpu("500m").mem("256Mi")
                .length(500_000)
                .build())
            .build());
        broker.addController(new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "batch", Namespace.DEFAULT, batchTemplate, batchReplicas));

        // ── Snapshot placement at t≈10s before terminateAt clears it ─────────
        final AtomicReference<List<Placement>> snap = new AtomicReference<>(List.of());
        broker.registerTick((Tick) clock -> {
            if (snap.get().isEmpty() && clock >= 10.0) {
                final var rows = new ArrayList<Placement>();
                for (KubernetesNode n : broker.getNodes()) {
                    for (KubernetesPod p : broker.placedPodsOnNode(n)) {
                        rows.add(new Placement(
                            p.getPodName(),
                            p.getLabels().get("app"),
                            n.getNodeName(),
                            n.getLabels().has("workload") ? n.getLabels().get("workload") : "-"));
                    }
                }
                snap.set(rows);
            }
        });

        sim.terminateAt(duration);
        final long t0 = System.nanoTime();
        sim.start();
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;

        // ── Aggregate ─────────────────────────────────────────────────────────
        int mlOnGpu = 0, mlTotal = 0, batchOnCpu = 0, batchTotal = 0, webTotal = 0;
        final Set<String> webNodes = new HashSet<>();
        for (Placement p : snap.get()) {
            switch (p.app == null ? "" : p.app) {
                case "ml" -> {
                    mlTotal++;
                    if ("gpu".equals(p.workload)) mlOnGpu++;
                }
                case "web" -> {
                    webTotal++;
                    webNodes.add(p.node);
                }
                case "batch" -> {
                    batchTotal++;
                    if ("cpu".equals(p.workload)) batchOnCpu++;
                }
                default -> { /* ignore */ }
            }
        }

        log("  Placement after t=10s (app → node @ workload-label):");
        log("  ─────────────────────────────────────────────────────");
        log("  %-10s %-7s %-10s %s", "POD", "APP", "NODE", "WORKLOAD");
        log("  ─────────────────────────────────────────────────────");
        for (Placement p : snap.get()) {
            log("  %-10s %-7s %-10s %s", p.pod, p.app, p.node, p.workload);
        }
        log("  ─────────────────────────────────────────────────────");
        log("  ml  on GPU nodes : %d / %d", mlOnGpu, mlTotal);
        log("  web distinct hosts: %d / %d  (anti-affinity HOSTNAME)",
            webNodes.size(), webTotal);
        log("  batch on CPU rack: %d / %d  (preferred workload=cpu)",
            batchOnCpu, batchTotal);
        log("  Sim clock        : %.2f s   wall: %d ms", sim.clock(), wallMs);
        log("");

        final boolean mlOk    = mlOnGpu == mlReplicas && mlTotal == mlReplicas;
        final boolean webOk   = webNodes.size() == webReplicas;
        final boolean batchOk = batchOnCpu >= batchTotal; // preferred → strong tendency
        if (mlOk && webOk && batchOk) {
            log("✅ VALIDATION PASSED: affinity + anti-affinity + tolerations all honoured.");
        } else {
            log("❌ VALIDATION FAILED: ml=%d/%d on GPU, web spread=%d/%d, batch=%d/%d on CPU.",
                mlOnGpu, mlReplicas, webNodes.size(), webReplicas, batchOnCpu, batchTotal);
        }
        return new Summary(mlOnGpu, mlTotal, webNodes.size(), webTotal,
            batchOnCpu, batchTotal, sim.clock(), wallMs);
    }

    private record Placement(String pod, String app, String node, String workload) {}

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
