package com.example.cspsim.interactive;

import com.example.cspsim.realtime.RunHandle;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.autoscaling.ClusterAutoscaler;
import org.cloudsimplus.kubernetes.autoscaling.HorizontalPodAutoscaler;
import org.cloudsimplus.kubernetes.autoscaling.NodePool;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.DeploymentController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.networking.queueing.MMcQueueModel;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.utilizationmodels.UtilizationModel;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.vms.Vm;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds and runs a parameterized Kubernetes simulation from a
 * {@link SimulationSpec}, emitting a {@link ClusterSnapshot} as a {@code tick}
 * SSE event on every controller tick and a {@code summary} event at the end.
 *
 * <p>The construction sequence mirrors the worked
 * {@code org.cloudsimplus.examples.kubernetes.K8sHPAExample} (nodes →
 * datacenter+scheduler → broker → deployments → HPA → ticks → run), but instead
 * of printing it reads broker state into structured snapshots.</p>
 */
@Service
public class InteractiveSimulationService {

    private static final int LATENCY_SAMPLES = 100;

    public record Summary(double simEndClock, long wallClockMs, int totalPods,
                          ClusterSnapshot finalState) {}

    /**
     * One latency model bound to a deployment's live replica count. When a
     * {@code shape} is present the arrival rate &lambda; follows the load curve
     * over time; otherwise the static {@code staticRate} is used.
     */
    private record Queue(String name, MMcQueueModel model, WorkloadShape shape, double staticRate) {
        double arrivalAt(final double t) {
            return shape != null ? shape.loadAt(t) : staticRate;
        }
    }

    public void runTo(SimulationSpec spec, RunHandle handle) throws IOException {
        if (spec == null || spec.deployments() == null || spec.deployments().isEmpty()) {
            throw new IllegalArgumentException("At least one deployment is required");
        }

        final double duration = spec.durationSeconds() != null ? spec.durationSeconds() : 120.0;
        final double tickInterval = spec.tickIntervalSeconds() != null ? spec.tickIntervalSeconds() : 1.0;
        final long throttleMillis = spec.throttleMillis() != null ? Math.max(0, spec.throttleMillis()) : 0L;
        final var policy = parsePolicy(spec.schedulerPolicy());

        final var sim = new CloudSimPlus();
        final List<KubernetesNode> nodes = buildNodes(spec);
        new DatacenterSimple(sim, nodes, new KubernetesScheduler(policy));

        final var broker = new KubernetesClusterBroker(sim)
            .setControllerTickIntervalSeconds(tickInterval);

        // Deployments (keep an insertion-ordered map for stable UI ordering).
        final Map<String, DeploymentController> deployments = new LinkedHashMap<>();
        final List<Queue> queues = new ArrayList<>();
        long seed = 42;
        for (final var d : spec.deployments()) {
            final WorkloadShape shape = d.workload() != null
                ? WorkloadShape.from(d.workload(), defaultLoad(d))
                : null;
            final var controller = buildDeployment(broker, d, shape, seed++);
            deployments.put(controller.getName(), controller);
            broker.addController(controller);

            final boolean wantLatency =
                (d.requestsPerSecond() != null && d.requestsPerSecond() > 0) || shape != null;
            if (wantLatency) {
                final double svcRate = d.serviceRatePerReplica() != null ? d.serviceRatePerReplica() : 50.0;
                final var model = new MMcQueueModel(svcRate, () -> currentReplicas(controller), seed++);
                final double staticRate = d.requestsPerSecond() != null ? d.requestsPerSecond() : 0.0;
                queues.add(new Queue(controller.getName(), model, shape, staticRate));
            }
        }

        // Autoscalers: HPA (replicas) and/or ClusterAutoscaler (nodes).
        if (spec.autoscalers() != null) {
            for (final var a : spec.autoscalers()) {
                if (a == null || a.kind() == null) continue;
                if ("HPA".equalsIgnoreCase(a.kind())) {
                    final var target = deployments.get(a.target());
                    if (target == null) {
                        throw new IllegalArgumentException("HPA target deployment not found: " + a.target());
                    }
                    final double cpuTarget = a.cpuTarget() != null ? a.cpuTarget() : 0.5;
                    final var hpa = HorizontalPodAutoscaler.of(target, cpuTarget)
                        .setMinReplicas(a.minReplicas() != null ? a.minReplicas() : 1)
                        .setMaxReplicas(a.maxReplicas() != null ? a.maxReplicas() : 10)
                        .setCooldownSeconds(a.cooldownSeconds() != null ? a.cooldownSeconds() : 10.0);
                    broker.registerTick(hpa);
                } else if ("ClusterAutoscaler".equalsIgnoreCase(a.kind())) {
                    broker.registerTick(buildClusterAutoscaler(broker, a));
                } else {
                    throw new IllegalArgumentException("Unknown autoscaler kind: " + a.kind());
                }
            }
        }

        // Per-tick snapshot emitter (also honours cancellation + paced playback).
        broker.registerTick((Tick) clock -> {
            if (handle.isCancelled()) { sim.terminate(); return; }
            try {
                handle.send("tick", snapshot(clock, broker, deployments, queues));
            } catch (IOException e) {
                sim.terminate();   // client disconnected — stop simulating
                return;
            }
            if (throttleMillis > 0) {
                try {
                    Thread.sleep(throttleMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    sim.terminate();
                }
            }
        });

        sim.terminateAt(duration);
        final long t0 = System.nanoTime();
        sim.start();
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;

        if (!handle.isCancelled()) {
            final var finalState = snapshot(sim.clock(), broker, deployments, queues);
            handle.send("summary", new Summary(sim.clock(), wallMs, broker.getPods().size(), finalState));
        }
    }

    // ── construction helpers ────────────────────────────────────────────────

    private static VmAllocationPolicyTopologyAware.Policy parsePolicy(String name) {
        if (name == null || name.isBlank()) {
            return VmAllocationPolicyTopologyAware.Policy.COST_OPTIMIZED;
        }
        try {
            return VmAllocationPolicyTopologyAware.Policy.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown scheduler policy: " + name);
        }
    }

    private static List<KubernetesNode> buildNodes(SimulationSpec spec) {
        final List<KubernetesNode> nodes = new ArrayList<>();
        if (spec.nodes() != null && !spec.nodes().isEmpty()) {
            int i = 1;
            for (final var n : spec.nodes()) {
                final String name = (n.name() != null && !n.name().isBlank()) ? n.name() : "worker-" + i;
                nodes.add(NodeBuilder.of(name)
                    .pes(n.pes() != null ? n.pes() : 8, n.mipsPerCore() != null ? n.mipsPerCore() : 1000)
                    .ram(n.ramMiB() != null ? n.ramMiB() : 16_384)
                    .rack(n.rack() != null ? n.rack() : "r" + i)
                    .build());
                i++;
            }
        } else {
            final int count = spec.nodeCount() != null && spec.nodeCount() > 0 ? spec.nodeCount() : 3;
            for (int i = 1; i <= count; i++) {
                nodes.add(NodeBuilder.of("worker-" + i).pes(8, 1000).ram(16_384).rack("r" + i).build());
            }
        }
        return nodes;
    }

    private static DeploymentController buildDeployment(KubernetesClusterBroker broker,
                                                        SimulationSpec.DeploymentSpec d,
                                                        WorkloadShape shape, long seed) {
        final String name = (d.name() != null && !d.name().isBlank()) ? d.name() : "app";
        final String image = d.image() != null ? d.image() : "app:1.0";
        final String cpu = d.cpu() != null ? d.cpu() : "500m";
        final String mem = d.mem() != null ? d.mem() : "256Mi";
        final long length = d.length() != null ? d.length() : 500_000L;
        final double util = d.cpuUtilization() != null ? d.cpuUtilization() : 0.8;
        final int replicas = d.replicas() != null && d.replicas() > 0 ? d.replicas() : 1;

        // A time-varying workload (when present) spreads load across the live
        // replica count; the supplier reads the controller created just below,
        // so it is wired through a one-element holder. Otherwise the deployment
        // keeps the constant per-pod utilization used previously.
        final UtilizationModel cpuModel;
        final DeploymentController[] ref = new DeploymentController[1];
        if (shape != null) {
            final var w = d.workload();
            final double peak = shape.peakLoad();
            final double cost = (w.costPerRequest() != null && w.costPerRequest() > 0)
                ? w.costPerRequest()
                : (peak > 0 ? 0.8 / peak : 0.0);   // auto-calibrate: peak load → ~80% per pod at 1 replica
            final double cv = w.jitterCv() != null ? w.jitterCv() : 0.1;
            cpuModel = new WorkloadUtilizationModel(shape, cost,
                () -> ref[0] == null ? 1 : currentReplicas(ref[0]), w.jitter(), cv, seed);
        } else {
            cpuModel = new UtilizationModelDynamic(util);
        }

        final var template = new PodTemplate(ord -> PodBuilder.of(name + "-" + ord)
            .label("app", name)
            .container(ContainerBuilder.of(name)
                .image(image)
                .cpu(cpu).mem(mem)
                .length(length)
                .cpuUtilization(cpuModel)
                .build())
            .build());

        final var controller = new DeploymentController(
            broker.getControllerManager().allocateUid(), name, Namespace.DEFAULT, template, replicas);
        ref[0] = controller;
        return controller;
    }

    /** Fallback peak load for a workload curve that omits {@code peak}. */
    private static double defaultLoad(SimulationSpec.DeploymentSpec d) {
        return d.requestsPerSecond() != null && d.requestsPerSecond() > 0 ? d.requestsPerSecond() : 1.0;
    }

    private static ClusterAutoscaler buildClusterAutoscaler(KubernetesClusterBroker broker,
                                                            SimulationSpec.AutoscalerSpec a) {
        final int min = a.minNodes() != null ? a.minNodes() : 0;
        final int max = a.maxNodes() != null ? a.maxNodes() : 3;
        final var tmpl = a.nodeTemplate();
        final int pes = tmpl != null && tmpl.pes() != null ? tmpl.pes() : 8;
        final int mips = tmpl != null && tmpl.mipsPerCore() != null ? tmpl.mipsPerCore() : 1000;
        final int ram = tmpl != null && tmpl.ramMiB() != null ? tmpl.ramMiB() : 16_384;

        final var counter = new AtomicInteger();
        final var pool = new NodePool("autopool",
            () -> NodeBuilder.of("auto-" + counter.incrementAndGet())
                .pes(pes, mips).ram(ram).rack("auto").build(),
            min, max);

        return new ClusterAutoscaler(broker, pool)
            .setCooldownSeconds(a.cooldownSeconds() != null ? a.cooldownSeconds() : 30.0)
            .setScaleDownAfterSeconds(a.scaleDownAfterSeconds() != null ? a.scaleDownAfterSeconds() : 600.0);
    }

    /** Current replica count via the deployment's active ReplicaSet (0 if none yet). */
    private static int currentReplicas(DeploymentController c) {
        final var rs = c.getActiveReplicaSet();
        return rs == null ? 0 : rs.currentReplicas();
    }

    // ── snapshot ──────────────────────────────────────────────────────────────

    private static ClusterSnapshot snapshot(double clock,
                                            KubernetesClusterBroker broker,
                                            Map<String, DeploymentController> deployments,
                                            List<Queue> queues) {
        // Live node list (sorted for stable UI ordering) so ClusterAutoscaler-
        // provisioned nodes appear and decommissioned ones drop out.
        final List<KubernetesNode> nodes = new ArrayList<>(broker.getNodes());
        nodes.sort(Comparator.comparing(KubernetesNode::getNodeName));

        final List<ClusterSnapshot.NodeView> nodeViews = new ArrayList<>(nodes.size());
        for (final var node : nodes) {
            final double total = node.getTotalMipsCapacity();
            final double reserved = node.getVmList().stream().mapToDouble(Vm::getTotalMipsCapacity).sum();
            final var pods = broker.placedPodsOnNode(node).stream()
                .map(p -> p.getPodName()).toList();
            nodeViews.add(new ClusterSnapshot.NodeView(
                node.getNodeName(),
                node.isSchedulable(),
                node.getPeList().size(),
                total,
                total > 0 ? Math.min(1.0, reserved / total) : 0.0,
                pods));
        }

        final List<ClusterSnapshot.DeploymentView> depViews = new ArrayList<>(deployments.size());
        deployments.forEach((name, c) ->
            depViews.add(new ClusterSnapshot.DeploymentView(name, c.getDesiredReplicas(), currentReplicas(c))));

        final List<ClusterSnapshot.ServiceView> svcViews = new ArrayList<>(queues.size());
        for (final var q : queues) {
            svcViews.add(latency(q, clock));
        }

        final int pending = (int) broker.getPods().stream().filter(p -> !p.isCreated()).count();
        return new ClusterSnapshot(clock, nodeViews.size(), pending, nodeViews, depViews, svcViews);
    }

    private static ClusterSnapshot.ServiceView latency(Queue q, double clock) {
        final double arrival = q.arrivalAt(clock);
        final double[] samples = new double[LATENCY_SAMPLES];
        int finite = 0;
        for (int i = 0; i < LATENCY_SAMPLES; i++) {
            final double rt = q.model().draw(arrival);
            if (Double.isFinite(rt)) samples[finite++] = rt;
        }
        if (finite == 0) {
            return new ClusterSnapshot.ServiceView(q.name(), -1, -1, true);
        }
        final double[] ok = Arrays.copyOf(samples, finite);
        Arrays.sort(ok);
        final boolean saturated = finite < LATENCY_SAMPLES; // some draws were infinite
        return new ClusterSnapshot.ServiceView(q.name(),
            percentileMs(ok, 0.50), percentileMs(ok, 0.95), saturated);
    }

    private static double percentileMs(double[] sortedSeconds, double p) {
        final int idx = Math.min(sortedSeconds.length - 1, (int) Math.floor(p * sortedSeconds.length));
        return Math.round(sortedSeconds[idx] * 1000.0 * 100.0) / 100.0; // seconds → ms, 2 dp
    }
}
