package com.example.cspsim.interactive;

import org.cloudsimplus.utilizationmodels.UtilizationModelAbstract;

import java.util.Random;
import java.util.function.IntSupplier;

/**
 * A time-varying, replica-spreading CPU utilization model. The per-pod CPU at
 * simulation time {@code t} is
 *
 * <pre>
 *   min( shape.loadAt(t) · costPerRequest / replicas(t), 1.0 )
 * </pre>
 *
 * so the load follows the chosen {@link WorkloadShape} over time <em>and</em>
 * spreads across the live replica count — letting the HPA traverse a
 * non-degenerate trajectory and settle at an equilibrium instead of pinning at
 * a constant value. This generalises the constant-rate
 * {@code org.cloudsimplus.utilizationmodels.UtilizationModelThroughput} and the
 * Online Boutique {@code LoadSpreadingModel} to an arbitrary load curve.
 *
 * <p>{@code costPerRequest} is the fraction of one pod's CPU consumed per unit
 * of load; see {@code UtilizationModelThroughput} for the calibration identity
 * {@code costPerRequest = N* · T / peakLoad}.</p>
 *
 * <p>Optional jitter perturbs the instantaneous load to model arrival
 * variability (CPU/HPA only — the attached latency model samples the curve
 * deterministically so percentile charts stay stable):</p>
 * <ul>
 *   <li>{@code POISSON}  — Normal({@code load}, {@code √load}) approximation to
 *       a Poisson arrival count (exact for large load).</li>
 *   <li>{@code GAUSSIAN} — multiplicative {@code load·(1 + cv·N(0,1))}.</li>
 * </ul>
 * The RNG is seeded for run-to-run reproducibility.
 */
public final class WorkloadUtilizationModel extends UtilizationModelAbstract {

    private final WorkloadShape shape;
    private final double costPerRequest;
    private final IntSupplier replicas;
    private final String jitter;     // "POISSON" | "GAUSSIAN" | null
    private final double jitterCv;
    private final Random rng;        // null when no jitter

    public WorkloadUtilizationModel(final WorkloadShape shape, final double costPerRequest,
                                    final IntSupplier replicas, final String jitter,
                                    final double jitterCv, final long seed) {
        this.shape = shape;
        this.costPerRequest = costPerRequest;
        this.replicas = replicas;
        this.jitter = jitter == null || jitter.isBlank() ? null : jitter.trim().toUpperCase(java.util.Locale.ROOT);
        this.jitterCv = jitterCv;
        this.rng = this.jitter == null ? null : new Random(seed);
    }

    @Override
    protected double getUtilizationInternal(final double time) {
        double load = shape.loadAt(time);
        if (rng != null && load > 0.0) {
            load = switch (jitter) {
                case "POISSON"  -> Math.max(0.0, load + Math.sqrt(load) * rng.nextGaussian());
                case "GAUSSIAN" -> Math.max(0.0, load * (1.0 + jitterCv * rng.nextGaussian()));
                default          -> load;
            };
        }
        final int n = Math.max(1, replicas.getAsInt());
        final double perPod = load * costPerRequest / n;
        return Math.max(0.0, Math.min(perPod, 1.0));
    }
}
