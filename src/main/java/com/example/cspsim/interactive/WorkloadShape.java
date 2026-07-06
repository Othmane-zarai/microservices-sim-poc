package com.example.cspsim.interactive;

import java.util.Locale;

/**
 * A time-varying load curve {@code load(t)}, expressed in abstract load units
 * (concurrent users or requests/second — the unit is cosmetic and folds into
 * the per-request CPU cost downstream). The curve is evaluated at the
 * simulation clock to drive both per-pod CPU (via
 * {@link WorkloadUtilizationModel}) and, when a latency model is attached, the
 * M/M/c arrival rate &lambda;.
 *
 * <p>Six shapes are supported:</p>
 * <ul>
 *   <li>{@code CONSTANT}  — flat {@code baseline} (back-compatible default).</li>
 *   <li>{@code RAMP_UP}   — linear {@code baseline → peak} over
 *       {@code [start, start+window]}, flat outside.</li>
 *   <li>{@code RAMP_DOWN} — linear {@code peak → baseline} over the same window.</li>
 *   <li>{@code STEP}      — {@code baseline} until {@code start}, then {@code peak}.</li>
 *   <li>{@code SPIKE}     — {@code baseline}, except {@code peak} during
 *       {@code [start, start+window]} (a burst), then back to {@code baseline}.</li>
 *   <li>{@code SINUSOID}  — {@code mid + amp·sin(2π·t/period)} with
 *       {@code mid=(peak+baseline)/2}, {@code amp=(peak−baseline)/2}.</li>
 * </ul>
 */
public final class WorkloadShape {

    public enum Kind { CONSTANT, RAMP_UP, RAMP_DOWN, STEP, SPIKE, SINUSOID }

    private final Kind kind;
    private final double baseline;
    private final double peak;
    private final double start;
    private final double window;
    private final double period;

    private WorkloadShape(final Kind kind, final double baseline, final double peak,
                          final double start, final double window, final double period) {
        this.kind = kind;
        this.baseline = baseline;
        this.peak = peak;
        this.start = Math.max(0.0, start);
        this.window = Math.max(1e-9, window);
        this.period = Math.max(1e-9, period);
    }

    /** A flat curve at {@code load} for all time (back-compatible default). */
    public static WorkloadShape constant(final double load) {
        return new WorkloadShape(Kind.CONSTANT, load, load, 0.0, 1.0, 1.0);
    }

    /** A linear ramp from {@code baseline} up to {@code peak} over {@code [start, start+window]}, then flat. */
    public static WorkloadShape rampUp(final double baseline, final double peak,
                                       final double start, final double window) {
        return new WorkloadShape(Kind.RAMP_UP, baseline, peak, start, window, 1.0);
    }

    /**
     * Builds a shape from a UI {@link SimulationSpec.WorkloadSpec}, applying
     * defaults. {@code defaultLoad} is used for {@code peak} when the spec omits
     * it (and for {@code baseline} of a ramp-down).
     */
    public static WorkloadShape from(final SimulationSpec.WorkloadSpec w, final double defaultLoad) {
        if (w == null || w.shape() == null || w.shape().isBlank()) {
            return constant(defaultLoad);
        }
        final Kind kind;
        try {
            kind = Kind.valueOf(w.shape().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown workload shape: " + w.shape());
        }
        final double baseline = w.baseline() != null ? w.baseline() : 0.0;
        final double peak     = w.peak() != null ? w.peak() : defaultLoad;
        final double start    = w.startSeconds()  != null ? w.startSeconds()  : 0.0;
        final double window   = w.windowSeconds()  != null ? w.windowSeconds()  : 60.0;
        final double period   = w.periodSeconds()  != null ? w.periodSeconds()  : 120.0;
        return new WorkloadShape(kind, baseline, peak, start, window, period);
    }

    /** Load at simulation time {@code t} seconds (clamped to ≥ 0). */
    public double loadAt(final double t) {
        final double v = switch (kind) {
            case CONSTANT  -> baseline;
            case RAMP_UP   -> ramp(t, baseline, peak);
            case RAMP_DOWN -> ramp(t, peak, baseline);
            case STEP      -> t < start ? baseline : peak;
            case SPIKE     -> (t >= start && t < start + window) ? peak : baseline;
            case SINUSOID  -> {
                final double mid = (peak + baseline) / 2.0;
                final double amp = (peak - baseline) / 2.0;
                yield mid + amp * Math.sin(2.0 * Math.PI * t / period);
            }
        };
        return Math.max(0.0, v);
    }

    /** Linear interpolation from {@code from} to {@code to} across the window. */
    private double ramp(final double t, final double from, final double to) {
        if (t <= start) return from;
        if (t >= start + window) return to;
        return from + ((t - start) / window) * (to - from);
    }

    /** The maximum load the curve can reach — used to auto-calibrate per-request cost. */
    public double peakLoad() {
        return Math.max(baseline, peak);
    }
}
