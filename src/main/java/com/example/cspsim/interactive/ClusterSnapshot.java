package com.example.cspsim.interactive;

import java.util.List;

/**
 * Structured cluster state at one controller tick, streamed as a {@code tick}
 * SSE event. The UI accumulates these into a timeline it can scrub and chart.
 */
public record ClusterSnapshot(
    double clock,
    int nodeCount,
    int pendingPods,
    List<NodeView> nodes,
    List<DeploymentView> deployments,
    List<ServiceView> services
) {
    public record NodeView(
        String name,
        boolean schedulable,
        int pes,
        double totalMips,
        double cpuReservedFraction,   // reserved MIPS / total MIPS, 0..1
        List<String> pods
    ) {}

    public record DeploymentView(String name, int desired, int current) {}

    /** Per-service latency from the M/M/c model (ms); -1 if saturated/unknown. */
    public record ServiceView(String name, double p50ms, double p95ms, boolean saturated) {}
}
