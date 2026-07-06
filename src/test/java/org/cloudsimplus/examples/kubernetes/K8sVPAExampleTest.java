package org.cloudsimplus.examples.kubernetes;

import org.cloudsimplus.kubernetes.autoscaling.VerticalPodAutoscaler.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sVPAExampleTest {

    @BeforeEach @AfterEach
    void clearProps() {
        System.clearProperty("k8s.nodes");
        System.clearProperty("k8s.replicas");
        System.clearProperty("k8s.vpa.targetCpu");
        System.clearProperty("k8s.vpa.cooldown");
        System.clearProperty("k8s.vpa.evict");
        System.clearProperty("k8s.vpa.mode");
        System.clearProperty("k8s.duration");
    }

    @Test
    void vpaRecommendsHigherCpuUnderSustainedLoad() {
        // Pods run at 90% CPU; VPA target=70% → expected ≈ 500 × 0.90 / 0.70 ≈ 643m.
        System.setProperty("k8s.nodes", "4");
        System.setProperty("k8s.replicas", "3");
        System.setProperty("k8s.vpa.targetCpu", "0.7");
        System.setProperty("k8s.vpa.cooldown", "10");
        System.setProperty("k8s.duration", "120");

        final var s = new K8sVPAExample().runAndReturnSummary();

        assertTrue(s.recommendedMilliCpu() > s.initialMilliCpu(),
            "VPA must recommend more than " + s.initialMilliCpu() + "m, got "
                + s.recommendedMilliCpu() + "m");
        assertTrue(s.recommendationUpdates() >= 1,
            "VPA must produce at least one recommendation update under sustained load");
    }

    @Test
    void vpaAutoModeResizesContainerCpuInPlaceWithoutEviction() {
        // AUTO mode must patch effectiveLimits in-place; recommendedMilliCpu still rises.
        System.setProperty("k8s.nodes", "4");
        System.setProperty("k8s.replicas", "3");
        System.setProperty("k8s.vpa.targetCpu", "0.7");
        System.setProperty("k8s.vpa.cooldown", "10");
        System.setProperty("k8s.vpa.mode", "AUTO");
        System.setProperty("k8s.vpa.evict", "false");
        System.setProperty("k8s.duration", "120");

        final var s = new K8sVPAExample().runAndReturnSummary();

        assertEquals(Mode.AUTO, s.mode(), "Summary must report AUTO mode");
        assertTrue(s.recommendedMilliCpu() > s.initialMilliCpu(),
            "VPA AUTO must recommend more CPU than " + s.initialMilliCpu() + "m");
        assertTrue(s.finalContainerMilliCpu() > s.initialMilliCpu(),
            "effectiveLimits must have been raised above " + s.initialMilliCpu() +
            "m in AUTO mode (got " + s.finalContainerMilliCpu() + "m)");
    }
}
