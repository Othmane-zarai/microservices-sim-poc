package com.example.cspsim.interactive;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the time-varying load curves ({@link WorkloadShape}) and the
 * replica-spreading per-pod CPU model ({@link WorkloadUtilizationModel}).
 */
class WorkloadModelTest {

    private static SimulationSpec.WorkloadSpec spec(String shape, double baseline, double peak,
                                                    double start, double window, double period) {
        return new SimulationSpec.WorkloadSpec(shape, "USERS", baseline, peak,
            start, window, period, null, null, null);
    }

    @Test
    void rampUpInterpolatesLinearly() {
        final var s = WorkloadShape.from(spec("RAMP_UP", 0, 100, 10, 40, 120), 1);
        assertEquals(0.0, s.loadAt(0), 1e-9, "flat at baseline before start");
        assertEquals(0.0, s.loadAt(10), 1e-9, "baseline at start");
        assertEquals(50.0, s.loadAt(30), 1e-9, "midpoint of the ramp");
        assertEquals(100.0, s.loadAt(50), 1e-9, "peak at end of window");
        assertEquals(100.0, s.loadAt(90), 1e-9, "flat at peak after window");
    }

    @Test
    void rampDownGoesPeakToBaseline() {
        final var s = WorkloadShape.from(spec("RAMP_DOWN", 10, 90, 0, 80, 120), 1);
        assertEquals(90.0, s.loadAt(0), 1e-9);
        assertEquals(50.0, s.loadAt(40), 1e-9);
        assertEquals(10.0, s.loadAt(80), 1e-9);
    }

    @Test
    void stepAndSpike() {
        final var step = WorkloadShape.from(spec("STEP", 5, 80, 60, 1, 120), 1);
        assertEquals(5.0, step.loadAt(59), 1e-9);
        assertEquals(80.0, step.loadAt(60), 1e-9);

        final var spike = WorkloadShape.from(spec("SPIKE", 5, 200, 30, 10, 120), 1);
        assertEquals(5.0, spike.loadAt(29), 1e-9);
        assertEquals(200.0, spike.loadAt(35), 1e-9, "inside the burst");
        assertEquals(5.0, spike.loadAt(40), 1e-9, "back to baseline after the burst");
    }

    @Test
    void sinusoidOscillatesBetweenBaselineAndPeak() {
        final var s = WorkloadShape.from(spec("SINUSOID", 0, 100, 0, 1, 120), 1);
        assertEquals(50.0, s.loadAt(0), 1e-9, "starts at mid");
        assertEquals(100.0, s.loadAt(30), 1e-6, "quarter period → peak");
        assertEquals(0.0, s.loadAt(90), 1e-6, "three-quarter period → baseline");
    }

    @Test
    void perPodCpuSpreadsAcrossReplicasAndClamps() {
        // peak 100, costPerRequest 0.01 → aggregate 1.0 of one pod's CPU at peak.
        final var shape = WorkloadShape.constant(100);
        final AtomicInteger replicas = new AtomicInteger(1);
        final var model = new WorkloadUtilizationModel(shape, 0.01, replicas::get, null, 0.0, 1L);

        assertEquals(1.0, model.getUtilization(0), 1e-9, "1 replica → fully loaded (clamped)");
        replicas.set(2);
        assertEquals(0.5, model.getUtilization(0), 1e-9, "2 replicas → load halves");
        replicas.set(4);
        assertEquals(0.25, model.getUtilization(0), 1e-9, "4 replicas → load quarters");
    }

    @Test
    void rampDrivesPerPodCpuDownOverTimeAsLoadAndReplicasGrow() {
        final var shape = WorkloadShape.from(spec("RAMP_UP", 0, 200, 0, 100, 120), 1);
        // costPerRequest auto-style: 0.8/peak so peak@1replica ≈ 0.8.
        final AtomicInteger replicas = new AtomicInteger(1);
        final var model = new WorkloadUtilizationModel(shape, 0.8 / 200.0, replicas::get, null, 0.0, 1L);

        final double early = model.getUtilization(10);   // low load, 1 replica
        final double peakOneReplica = model.getUtilization(100);
        assertTrue(peakOneReplica > early, "per-pod CPU rises with load at fixed replicas");
        assertEquals(0.8, peakOneReplica, 1e-9, "peak load at 1 replica hits the calibrated ~80%");

        replicas.set(4);
        assertTrue(model.getUtilization(100) < peakOneReplica, "scaling out lowers per-pod CPU at peak load");
    }
}
