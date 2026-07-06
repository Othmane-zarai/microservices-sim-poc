package org.cloudsimplus.examples.kubernetes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the PlanetLab trace-driven example. Tagged "slow" since
 * loading 20 trace files and running 600s of simulated time takes a few
 * seconds wall-clock.
 */
@Tag("slow")
class K8sPlanetLabExampleTest {

    @BeforeEach @AfterEach
    void clearProps() {
        System.clearProperty("k8s.nodes");
        System.clearProperty("k8s.replicas");
        System.clearProperty("k8s.duration");
        System.clearProperty("k8s.tickInterval");
        System.clearProperty("k8s.schedulingInterval");
        System.clearProperty("k8s.containerLengthMI");
        System.clearProperty("k8s.cpuMultiplier");
    }

    @Test
    void traceDrivenLoadProducesNonZeroDemand() {
        // Smaller scenario to keep wall-clock under ~10s: 10 nodes, 20 pods,
        // 600s of simulated time, 4.5x multiplier so the trace is non-trivial.
        System.setProperty("k8s.nodes", "10");
        System.setProperty("k8s.replicas", "20");
        System.setProperty("k8s.duration", "600");
        System.setProperty("k8s.schedulingInterval", "300");
        System.setProperty("k8s.cpuMultiplier", "4.5");

        final var s = new K8sPlanetLabExample().runAndReturnSummary();

        assertEquals(10, s.nodeCount(), "10 nodes per knob");
        assertEquals(20, s.podCount(), "20 pods per knob");
        assertTrue(s.sampleCount() >= 1,
            "PlanetLab traces sampled every 300s; expect at least one sample");
        assertTrue(s.avgDemand() > 0,
            "PlanetLab trace at 4.5x amplification must yield non-zero average demand "
                + "(got " + s.avgDemand() + ")");
    }
}
