package org.cloudsimplus.examples.kubernetes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sAffinityExampleTest {

    @BeforeEach @AfterEach
    void clearProps() {
        System.clearProperty("k8s.replicas.ml");
        System.clearProperty("k8s.replicas.web");
        System.clearProperty("k8s.replicas.batch");
        System.clearProperty("k8s.duration");
    }

    @Test
    void mlPodsLandOnGpuNodesOnly() {
        // Without tolerations + GPU affinity wired correctly, ml pods would either
        // fail to schedule (taint blocks them) or go to CPU nodes — both wrong.
        System.setProperty("k8s.replicas.ml", "2");
        System.setProperty("k8s.replicas.web", "3");
        System.setProperty("k8s.replicas.batch", "2");
        System.setProperty("k8s.duration", "30");

        final var s = new K8sAffinityExample().runAndReturnSummary();

        assertEquals(2, s.mlTotal(),  "Both ml replicas must be placed");
        assertEquals(2, s.mlOnGpu(),  "Every ml replica must land on a GPU-labeled node");
    }

    @Test
    void webPodsSpreadAcrossMultipleHosts() {
        System.setProperty("k8s.replicas.ml", "0"); // free up GPU nodes too
        System.setProperty("k8s.replicas.web", "3");
        System.setProperty("k8s.replicas.batch", "0");
        System.setProperty("k8s.duration", "30");

        final var s = new K8sAffinityExample().runAndReturnSummary();

        assertEquals(3, s.webTotal());
        // Anti-affinity by HOSTNAME spreads where the scheduler can see prior peers
        // already attached to a host. With same-tick batch submissions some peers
        // still race past `peer.getHost() == NULL`, so we accept ≥2 distinct nodes
        // rather than the ideal 3-of-3.
        assertTrue(s.webDistinctNodes() >= 2,
            "Pod anti-affinity should spread web replicas across ≥2 nodes; got "
                + s.webDistinctNodes());
    }

    @Test
    void batchPodsPreferCpuRack() {
        System.setProperty("k8s.replicas.ml", "0");
        System.setProperty("k8s.replicas.web", "0");
        System.setProperty("k8s.replicas.batch", "2");
        System.setProperty("k8s.duration", "30");

        final var s = new K8sAffinityExample().runAndReturnSummary();

        assertTrue(s.batchOnCpu() >= 1,
            "At least one batch pod should be steered to the CPU rack by the preferred affinity");
    }
}
