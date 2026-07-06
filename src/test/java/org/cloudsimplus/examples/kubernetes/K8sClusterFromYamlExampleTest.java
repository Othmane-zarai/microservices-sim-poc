package org.cloudsimplus.examples.kubernetes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sClusterFromYamlExampleTest {

    @BeforeEach @AfterEach
    void clearProps() {
        System.clearProperty("k8syaml.config");
        System.clearProperty("k8syaml.duration");
    }

    @Test
    void defaultClusterLoadsAndRuns() {
        // Use the default YAML (cluster-10nodes.yaml) and a short duration.
        System.setProperty("k8syaml.duration", "30");
        final var s = new K8sClusterFromYamlExample().runAndReturnSummary();

        assertEquals(10, s.nodeCount(),
            "Default cluster YAML declares 10 nodes");
        assertTrue(s.deploymentCount() >= 1,
            "Default cluster YAML declares at least one Deployment");
        assertTrue(s.placedPodCount() > 0,
            "At least one pod should land on the cluster within 30s "
                + "(placed=" + s.placedPodCount() + ", unschedulable=" + s.unschedulablePodCount() + ")");
        assertTrue(s.simEndClock() >= 30.0);
    }
}
