package com.example.cspsim.interactive;

import java.util.List;

/**
 * User-supplied description of a parameterized Kubernetes simulation, posted as
 * JSON from the UI. All fields are nullable; {@link InteractiveSimulationService}
 * applies defaults. Either {@link #nodeCount} (N identical nodes) or an explicit
 * {@link #nodes} list may be given; deployments are required.
 */
public record SimulationSpec(
    Integer nodeCount,
    List<NodeSpec> nodes,
    List<DeploymentSpec> deployments,
    List<AutoscalerSpec> autoscalers,
    String schedulerPolicy,        // COST_OPTIMIZED (default), LATENCY_AWARE, ...
    Double durationSeconds,        // default 120
    Double tickIntervalSeconds,    // default 1.0
    Long throttleMillis            // per-tick sleep for paced playback; default 0 = full speed
) {

    public record NodeSpec(String name, Integer pes, Integer mipsPerCore, Integer ramMiB, String rack) {}

    /**
     * A Deployment. {@code requestsPerSecond} (optional) turns on an M/M/c
     * latency model for the service so the UI can chart p50/p95 response time.
     * {@code workload} (optional) replaces the constant {@code cpuUtilization}
     * with a time-varying, replica-spreading load curve (see
     * {@link WorkloadUtilizationModel}); when present it also drives the latency
     * model's arrival rate over time. When {@code workload} is null the
     * deployment uses the constant {@code cpuUtilization} as before.
     */
    public record DeploymentSpec(
        String name,
        Integer replicas,
        String cpu,                // Kubernetes notation, e.g. "500m"
        String mem,                // e.g. "256Mi"
        String image,
        Double cpuUtilization,     // sustained per-pod CPU fraction 0..1 (default 0.8)
        Long length,               // cloudlet length (default 500_000 so pods outlive the run)
        Double requestsPerSecond,  // optional: enables latency model
        Double serviceRatePerReplica, // req/s one replica serves (default 50)
        WorkloadSpec workload      // optional: time-varying load curve (overrides cpuUtilization)
    ) {}

    /**
     * A time-varying load curve attached to a deployment. {@code shape} selects
     * the curve (CONSTANT | RAMP_UP | RAMP_DOWN | STEP | SPIKE | SINUSOID);
     * {@code baseline}/{@code peak} are load levels (users or req/s, per
     * {@code unit}); {@code startSeconds}/{@code windowSeconds} time the
     * ramp/step/spike; {@code periodSeconds} sets the sinusoid period.
     * {@code costPerRequest} (optional) calibrates load→per-pod-CPU; when
     * omitted it is auto-derived from {@code peak}. {@code jitter}
     * (null | POISSON | GAUSSIAN) adds arrival variability with coefficient
     * {@code jitterCv} (GAUSSIAN only; default 0.1).
     */
    public record WorkloadSpec(
        String shape,
        String unit,               // "USERS" | "RPS" (cosmetic label)
        Double baseline,
        Double peak,
        Double startSeconds,
        Double windowSeconds,
        Double periodSeconds,
        Double costPerRequest,
        String jitter,
        Double jitterCv
    ) {}

    /**
     * An autoscaler. {@code kind} is "HPA" (scales a deployment's replicas) or
     * "ClusterAutoscaler" (provisions/decommissions nodes for pending pods).
     * HPA uses target/min/maxReplicas/cpuTarget; ClusterAutoscaler uses
     * min/maxNodes/scaleDownAfterSeconds/nodeTemplate. {@code cooldownSeconds}
     * applies to both.
     */
    public record AutoscalerSpec(
        String kind,
        // HPA
        String target,             // deployment name
        Integer minReplicas,
        Integer maxReplicas,
        Double cpuTarget,          // target avg CPU fraction 0..1 (default 0.5)
        Double cooldownSeconds,
        // ClusterAutoscaler
        Integer minNodes,
        Integer maxNodes,
        Double scaleDownAfterSeconds,
        NodeSpec nodeTemplate      // shape of nodes the pool provisions
    ) {}
}
