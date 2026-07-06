package org.cloudsimplus.examples.kubernetes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sDaemonSetExampleTest {

    @BeforeEach @AfterEach
    void clearProps() {
        System.clearProperty("k8s.duration");
    }

    @Test
    void exampleRunsAndReportsExpectedClusterTopology() {
        // Smoke test: the example's hard-coded topology has 4 nodes (2
        // role=monitoring, 2 unlabeled). The DaemonSet selector matches the
        // monitoring nodes only. We assert the cluster shape and that the
        // example completes without throwing — finer-grained DaemonSet
        // placement assertions live in cloudsimplus' KubernetesAdvancedTest.
        System.setProperty("k8s.duration", "60");
        final var s = new K8sDaemonSetExample().runAndReturnSummary();

        assertEquals(4, s.totalNodes(), "Cluster has 4 fixed nodes");
        assertEquals(2, s.monitoringNodes(), "Two nodes carry role=monitoring");
        assertTrue(s.simEndClock() >= 60.0,
            "Sim should run to terminateAt=60s");
    }
}
