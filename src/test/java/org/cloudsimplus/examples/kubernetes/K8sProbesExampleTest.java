package org.cloudsimplus.examples.kubernetes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sProbesExampleTest {

    @BeforeEach @AfterEach
    void clearProps() {
        System.clearProperty("k8s.nodes");
        System.clearProperty("k8s.probes.healthyReplicas");
        System.clearProperty("k8s.probes.unhealthyReplicas");
        System.clearProperty("k8s.probes.readinessFailures");
        System.clearProperty("k8s.probes.period");
        System.clearProperty("k8s.duration");
    }

    @Test
    void healthyPodsBecomeReadyAfterReadinessProbeSucceeds() {
        // 2 healthy pods, no unhealthy stream — focus only on readiness flips.
        System.setProperty("k8s.nodes", "2");
        System.setProperty("k8s.probes.healthyReplicas", "2");
        System.setProperty("k8s.probes.unhealthyReplicas", "0");
        System.setProperty("k8s.probes.readinessFailures", "1");
        System.setProperty("k8s.probes.period", "1.0");
        System.setProperty("k8s.duration", "30");

        final var s = new K8sProbesExample().runAndReturnSummary();

        assertEquals(2, s.readyAtEnd(), "Both healthy pods must end Ready");
        assertEquals(0, s.livenessRestarts(),
            "No liveness probes when unhealthyReplicas=0");
    }

    @Test
    void failingLivenessProbeTriggersRestarts() {
        System.setProperty("k8s.nodes", "2");
        System.setProperty("k8s.probes.healthyReplicas", "1");
        System.setProperty("k8s.probes.unhealthyReplicas", "1");
        System.setProperty("k8s.probes.readinessFailures", "1");
        System.setProperty("k8s.probes.period", "2.0");
        System.setProperty("k8s.duration", "30");

        final var s = new K8sProbesExample().runAndReturnSummary();

        assertTrue(s.livenessRestarts() >= 1,
            "Failing liveness probe must trigger at least one restart over 30s");
    }
}
