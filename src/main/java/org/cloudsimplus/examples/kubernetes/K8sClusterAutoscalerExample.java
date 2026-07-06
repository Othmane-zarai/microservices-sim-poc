package org.cloudsimplus.examples.kubernetes;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.autoscaling.ClusterAutoscaler;
import org.cloudsimplus.kubernetes.autoscaling.NodePool;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.DeploymentController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates {@link ClusterAutoscaler}: the cluster starts with too few
 * nodes for the requested replicas, the scheduler marks the unfittable pods
 * <i>unschedulable</i>, and the autoscaler provisions fresh nodes from a
 * {@link NodePool} until everything fits.
 *
 * <p>Layout:</p>
 * <pre>
 *   initial nodes : k8s.initialNodes (default 2; each 4 PE × 1000 MIPS, 8 GiB)
 *   pool capacity : k8s.poolMax     (default 4 extra nodes available)
 *   replicas      : k8s.replicas    (default 8 — exceeds initial capacity)
 * </pre>
 *
 * <p>Knobs (JVM system properties):</p>
 * <pre>
 *   -Dk8s.initialNodes=2         starting nodes (deliberately undersized)
 *   -Dk8s.poolMax=4              maximum nodes the pool may add
 *   -Dk8s.replicas=8             Deployment desired replica count
 *   -Dk8s.ca.cooldown=5          cooldown between CA actions (sec)
 *   -Dk8s.ca.scaleDownAfter=300  idle window before scale-down (sec)
 *   -Dk8s.duration=120           terminateAt() in simulated seconds
 * </pre>
 */
public class K8sClusterAutoscalerExample {

    public record Summary(int initialNodes, int finalNodes,
                          int provisionedByPool, int replicas,
                          int placedPods, int unschedulableAtEnd,
                          double simEndClock, long wallClockMs) {}

    public static void main(String[] args) {
        new K8sClusterAutoscalerExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        suppressSimLogs();

        final int    initialNodes = intProp("k8s.initialNodes", 2);
        final int    poolMax      = intProp("k8s.poolMax", 4);
        final int    replicas     = intProp("k8s.replicas", 8);
        final double cooldown     = doubleProp("k8s.ca.cooldown", 5.0);
        final double scaleDownAfter = doubleProp("k8s.ca.scaleDownAfter", 300.0);
        final double duration     = doubleProp("k8s.duration", 120.0);

        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s Cluster Autoscaler  —  Provision nodes on demand   ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : Start undersized, deploy more pods than fit, observe CA grow the cluster.");
        log("  Tests: Unschedulable detection → NodePool spawn → datacenter registration.");
        log("  Knobs: initialNodes=%d  poolMax=%d  replicas=%d  duration=%.0fs",
            initialNodes, poolMax, replicas, duration);
        log("         caCooldown=%.0fs  scaleDownAfter=%.0fs", cooldown, scaleDownAfter);
        log("");

        final var sim = new CloudSimPlus();

        // ── Initial (deliberately undersized) cluster ─────────────────────────
        final var nodes = new ArrayList<KubernetesNode>(initialNodes);
        for (int i = 1; i <= initialNodes; i++) {
            nodes.add(NodeBuilder.of("worker-" + i)
                .pes(4, 1000).ram(8_192).rack("r" + i).build());
        }

        new DatacenterSimple(sim, nodes,
            new KubernetesScheduler(VmAllocationPolicyTopologyAware.Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim)
            .setControllerTickIntervalSeconds(1.0);

        // ── NodePool: factory that produces fresh, identically-sized nodes ────
        final AtomicInteger poolCounter = new AtomicInteger();
        final var pool = new NodePool(
            "auto-general",
            () -> NodeBuilder.of("auto-" + poolCounter.incrementAndGet())
                .pes(4, 1000).ram(8_192).rack("auto").build(),
            0, poolMax);

        final var ca = new ClusterAutoscaler(broker, pool)
            .setCooldownSeconds(cooldown)
            .setScaleDownAfterSeconds(scaleDownAfter);
        broker.registerTick(ca);

        // ── Workload: more replicas than the initial cluster can hold ────────
        final var template = new PodTemplate(ord -> PodBuilder.of("web-" + ord)
            .label("app", "web")
            .container(ContainerBuilder.of("nginx")
                .image("nginx:1.25")
                .cpu("1500m").mem("1024Mi") // 3-PE pods → 2 nodes ≈ 2 pods, rest pending
                .length(500_000)
                .build())
            .build());

        final var deployment = new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "web", Namespace.DEFAULT, template, replicas);
        broker.addController(deployment);

        // ── Timeline: log node-count growth events ────────────────────────────
        final List<String> timeline = new ArrayList<>();
        final int[] lastNodeCount = {initialNodes};
        final int[] lastUnsched   = {0};
        broker.registerTick((Tick) clock -> {
            final int n = broker.getNodes().size();
            final int unsched = (int) broker.getPods().stream()
                .filter(KubernetesPod::isUnschedulable).count();
            if (n != lastNodeCount[0] || (clock <= 5.0 && unsched != lastUnsched[0])) {
                lastNodeCount[0] = n;
                lastUnsched[0] = unsched;
                final String tag = n > initialNodes ? "  ↑ SCALE-UP" : "";
                timeline.add(String.format(
                    "  t=%5.1fs │ nodes=%-2d │ provisioned=%-2d │ unschedulable=%-2d%s",
                    clock, n, ca.getProvisioned(), unsched, tag));
            }
        });

        sim.terminateAt(duration);
        final long t0 = System.nanoTime();
        sim.start();
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;

        final int finalNodeCount = broker.getNodes().size();
        final int placed = (int) broker.getPods().stream()
            .filter(p -> p.getHost() != null
                && p.getHost() != org.cloudsimplus.hosts.Host.NULL)
            .count();
        final int unschedAtEnd = (int) broker.getPods().stream()
            .filter(KubernetesPod::isUnschedulable).count();

        log("  Cluster Autoscaler timeline:");
        log("  ─────────────────────────────────────────────────────────");
        if (timeline.isEmpty()) {
            log("  (no growth events — cluster fit the workload from the start)");
        } else {
            timeline.forEach(System.out::println);
        }
        log("  ─────────────────────────────────────────────────────────");
        log("  Initial nodes        : %d", initialNodes);
        log("  Final nodes          : %d  (CA provisioned %d from pool 'auto-general')",
            finalNodeCount, ca.getProvisioned());
        log("  Pods placed          : %d / %d", placed, replicas);
        log("  Pods unschedulable   : %d", unschedAtEnd);
        log("  Sim clock            : %.2f s   wall: %d ms", sim.clock(), wallMs);
        log("");

        final boolean grew = finalNodeCount > initialNodes;
        final boolean placedAll = placed == replicas;
        if (grew && placedAll) {
            log("✅ VALIDATION PASSED: CA grew the cluster from %d → %d nodes; all %d replicas placed.",
                initialNodes, finalNodeCount, replicas);
        } else {
            log("❌ VALIDATION FAILED: grew=%s, placed=%d/%d (expected growth + all placed).",
                grew, placed, replicas);
        }
        return new Summary(initialNodes, finalNodeCount, ca.getProvisioned(),
            replicas, placed, unschedAtEnd, sim.clock(), wallMs);
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
