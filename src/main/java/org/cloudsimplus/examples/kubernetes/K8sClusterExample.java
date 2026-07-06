package org.cloudsimplus.examples.kubernetes;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.KubernetesService;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.DeploymentController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.controllers.UpdateStrategy;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.networking.Ingress;
import org.cloudsimplus.kubernetes.networking.NetworkPolicy;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.kubernetes.security.ConfigMap;
import org.cloudsimplus.kubernetes.security.Role;
import org.cloudsimplus.kubernetes.security.RoleBinding;
import org.cloudsimplus.kubernetes.security.Secret;
import org.cloudsimplus.kubernetes.security.ServiceAccount;
import org.cloudsimplus.kubernetes.storage.PersistentVolume;
import org.cloudsimplus.kubernetes.storage.PersistentVolumeClaim;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Smallest working scenario built on the upstream Kubernetes simulation layer:
 * N worker nodes in one datacenter, a single Deployment of R nginx replicas,
 * run for D simulated seconds. After termination, prints which pod landed on
 * which node and how long the run took in wall-clock.
 *
 * <p>The example also exercises the post-review feature set added to the
 * Kubernetes layer so a smoke run touches every wiring path:</p>
 * <ul>
 *   <li>A {@code ConfigMap}, {@code Secret}, and {@code ServiceAccount} are
 *       registered in the {@code default} namespace; a {@code Role} +
 *       {@code RoleBinding} grant the SA read access. The pod template
 *       declares the corresponding mounts via
 *       {@code KubernetesPod#mountConfigMap}/{@code mountSecret}/
 *       {@code setServiceAccountName}, which forces the kubelet's pre-flight
 *       check to fire on every pod.</li>
 *   <li>A {@code PersistentVolume} (10 GiB) is bound to a
 *       {@code PersistentVolumeClaim} that each pod requires; the broker's
 *       first-fit binder runs at PVC registration time.</li>
 *   <li>A {@code KubernetesService} fronts the Deployment so the
 *       {@code NetworkPolicy} (allow-by-default) and {@code Ingress}
 *       host/path rule have something to bind to.</li>
 * </ul>
 *
 * <p>Knobs (JVM system properties — defaults match the original 3/3/60 baseline):</p>
 * <pre>
 *   -Dk8s.nodes=3            number of worker nodes (each 4 PE × 1000 MIPS, 8 GiB)
 *   -Dk8s.replicas=3         Deployment desired replica count
 *   -Dk8s.duration=60        terminateAt() in simulated seconds
 *   -Dk8s.tickInterval=1.0   broker controller-tick interval in simulated seconds
 *   -Dk8s.quiet=true         suppress CloudSim Plus internal INFO logs (default true)
 * </pre>
 *
 * <p>Snapshots placement mid-run via a Tick because terminateAt() destroys the
 * pods on shutdown — querying the broker afterwards yields an empty list.</p>
 */
public class K8sClusterExample {

    /**
     * Result record exposed by {@link #runAndReturnSummary()} so smoke tests
     * can assert on simulation outcomes without parsing stdout.
     */
    public record Summary(int nodeCount, int podCount,
                          List<Placement> placements,
                          double simEndClock, long wallClockMs,
                          boolean ingressResolved, boolean pvcBound) {}

    public static void main(String[] args) {
        final var summary = new K8sClusterExample().runAndReturnSummary();
        printSummary(summary);
    }

    /** Backward-compatible entry point used by tests that don't care about the summary. */
    public Summary run() {
        return runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        if (boolProp("k8s.quiet", true)) {
            suppressSimLogs();
        }

        final int nodeCount = intProp("k8s.nodes", 3);
        final int replicas = intProp("k8s.replicas", 3);
        final double duration = doubleProp("k8s.duration", 60.0);
        final double tickInterval = doubleProp("k8s.tickInterval", 1.0);

        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s Cluster Example  —  Smallest end-to-end scenario   ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : Run the smallest working scenario (N nodes, 1 Deployment with R replicas).");
        log("  Tests: scheduler placement; ConfigMap/Secret/SA pre-flight;");
        log("         PV/PVC binding; NetworkPolicy registration; Ingress routing.");
        log("  Knobs: nodes=%d  replicas=%d  duration=%.1fs  tickInterval=%.2fs",
            nodeCount, replicas, duration, tickInterval);
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
            .setControllerTickIntervalSeconds(tickInterval);

        // ─── Configuration / Security / Storage registries ────────────────
        // Registering these BEFORE submitting the Deployment ensures the
        // kubelet pre-flight finds every dependency at first reconcile and
        // pods transition straight from PENDING → RUNNING. Reverse the
        // order to observe the pod sitting in PENDING until the deps land.
        broker.addConfigMap(new ConfigMap("web-config", Namespace.DEFAULT)
            .putData("server.port", "8080")
            .putData("log.level", "info"));
        broker.addSecret(new Secret("web-tls", Namespace.DEFAULT)
            .putData("cert", new byte[]{1, 2, 3, 4}));
        final var sa = new ServiceAccount("web-sa", Namespace.DEFAULT);
        broker.addServiceAccount(sa);
        broker.addRole(new Role("web-reader", Namespace.DEFAULT)
            .addRule("get pods")
            .addRule("get services"));
        broker.addRoleBinding(new RoleBinding(
            "web-sa-reader", Namespace.DEFAULT,
            new Role("web-reader", Namespace.DEFAULT), sa));

        broker.addPersistentVolume(new PersistentVolume("data-pv", 10_000));
        final var pvc = new PersistentVolumeClaim("web-data", Namespace.DEFAULT, 1_000);
        broker.addPersistentVolumeClaim(pvc);  // first-fit binds against data-pv

        // ─── Deployment template (with declared mounts) ───────────────────
        final var template = new PodTemplate(ord -> PodBuilder.of("web-" + ord)
            .label("app", "web")
            .container(ContainerBuilder.of("nginx")
                .image("nginx:1.21")
                .cpu("500m").mem("256Mi")
                .length(50_000)
                .build())
            .build()
            .mountConfigMap("web-config")
            .mountSecret("web-tls")
            .setServiceAccountName("web-sa")
            .requirePersistentVolumeClaim("web-data"));

        final var deployment = new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "web",
            Namespace.DEFAULT,
            template,
            replicas
        ).setStrategy(UpdateStrategy.RollingUpdate.defaults());
        broker.addController(deployment);

        // ─── Networking: Service, NetworkPolicy, Ingress ──────────────────
        final var webService = new KubernetesService(
            "web", Namespace.DEFAULT, LabelSelector.matchLabel("app", "web"))
            .setType(KubernetesService.ServiceType.ClusterIP);
        broker.addService(webService);

        // Default-allow ingress policy targeting "app=web" pods. Flip
        // setIngressAllowed(false) to demonstrate request drops.
        broker.addNetworkPolicy(new NetworkPolicy(
            "web-allow", Namespace.DEFAULT,
            LabelSelector.matchLabel("app", "web"))
            .setIngressAllowed(true));

        final var ingress = new Ingress("web-ingress", Namespace.DEFAULT)
            .addRule(new Ingress.IngressRule("example.com", "/", webService));
        broker.addIngress(ingress);

        // ─── Mid-run placement snapshot ───────────────────────────────────
        final AtomicReference<List<Placement>> snapshot = new AtomicReference<>(List.of());
        broker.registerTick((Tick) clock -> {
            if (snapshot.get().isEmpty() && clock >= 5.0) {
                final var current = new ArrayList<Placement>();
                for (KubernetesNode node : broker.getNodes()) {
                    for (KubernetesPod pod : broker.placedPodsOnNode(node)) {
                        current.add(new Placement(
                            pod.getPodName(),
                            pod.getNamespace().getName(),
                            node.getNodeName(),
                            node.getRackId()));
                    }
                }
                snapshot.set(current);
            }
        });

        sim.terminateAt(duration);

        final long startNs = System.nanoTime();
        sim.start();
        final long wallClockMs = (System.nanoTime() - startNs) / 1_000_000L;

        final boolean ingressResolved =
            broker.routeIngress("example.com", "/").isPresent();

        return new Summary(nodes.size(), broker.getPods().size(),
            snapshot.get(), sim.clock(), wallClockMs,
            ingressResolved, pvc.isBound());
    }

    private static int intProp(String key, int defaultValue) {
        final var raw = System.getProperty(key);
        return raw == null ? defaultValue : Integer.parseInt(raw);
    }

    private static double doubleProp(String key, double defaultValue) {
        final var raw = System.getProperty(key);
        return raw == null ? defaultValue : Double.parseDouble(raw);
    }

    private static boolean boolProp(String key, boolean defaultValue) {
        final var raw = System.getProperty(key);
        return raw == null ? defaultValue : Boolean.parseBoolean(raw);
    }

    private static void suppressSimLogs() {
        try {
            ((ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("org.cloudsimplus"))
                .setLevel(ch.qos.logback.classic.Level.WARN);
        } catch (ClassCastException ignored) {
            // SLF4J binding isn't logback in this runtime — skip silently.
        }
    }

    private static void log(String fmt, Object... args) {
        if (args.length == 0) System.out.println(fmt);
        else System.out.printf(fmt + "%n", args);
    }

    private static void printSummary(Summary s) {
        log("  Pod → node placement (mid-run snapshot):");
        log("  ─────────────────────────────────────────────────────────");
        log("  %-12s %-10s %-12s %s", "POD", "NAMESPACE", "NODE", "RACK");
        log("  ─────────────────────────────────────────────────────────");
        // Avoid printing 1000s of rows when replicas is cranked up
        final int rowLimit = 25;
        int shown = 0;
        for (Placement p : s.placements) {
            if (shown++ >= rowLimit) {
                log("  ... and %d more pods", s.placements.size() - rowLimit);
                break;
            }
            log("  %-12s %-10s %-12s %s", p.pod, p.namespace, p.node, p.rack);
        }
        log("  ─────────────────────────────────────────────────────────");
        log("  nodes=%d  pods=%d  simEndClock=%.2fs  wallClockMs=%d",
            s.nodeCount, s.podCount, s.simEndClock, s.wallClockMs);
        log("  pvcBound=%s  ingressResolved=%s", s.pvcBound, s.ingressResolved);
        log("");

        // Explicit Validation
        final int expectedReplicas = intProp("k8s.replicas", 3);
        final boolean placementOk = s.podCount == expectedReplicas && !s.placements.isEmpty();
        if (placementOk && s.pvcBound && s.ingressResolved) {
            log("✅ VALIDATION PASSED: all %d replicas placed; PVC bound; Ingress route resolves.",
                expectedReplicas);
        } else {
            final var details = new java.util.ArrayList<String>();
            if (!placementOk) {
                details.add("placement: expected " + expectedReplicas + " pods, got " + s.podCount);
            }
            if (!s.pvcBound) details.add("PVC 'web-data' not bound");
            if (!s.ingressResolved) details.add("Ingress example.com/ did not resolve");
            log("❌ VALIDATION FAILED: %s", String.join("; ", details));
        }
    }

    public record Placement(String pod, String namespace, String node, String rack) {}
}
