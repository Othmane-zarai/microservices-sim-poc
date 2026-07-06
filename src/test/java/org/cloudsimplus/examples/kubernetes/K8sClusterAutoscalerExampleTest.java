package org.cloudsimplus.examples.kubernetes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sClusterAutoscalerExampleTest {

    @BeforeEach @AfterEach
    void clearProps() {
        System.clearProperty("k8s.initialNodes");
        System.clearProperty("k8s.poolMax");
        System.clearProperty("k8s.replicas");
        System.clearProperty("k8s.ca.cooldown");
        System.clearProperty("k8s.ca.scaleDownAfter");
        System.clearProperty("k8s.duration");
    }

    @Test
    void clusterAutoscalerProvisionsExtraNodesUntilWorkloadFits() {
        // 2 initial nodes × 4 PE = 8 PEs total. Pods request 1500m → ~5.3 PEs free,
        // so only 2-3 pods fit initially. Expect CA to add nodes to fit 8 replicas.
        System.setProperty("k8s.initialNodes", "2");
        System.setProperty("k8s.poolMax", "4");
        System.setProperty("k8s.replicas", "8");
        System.setProperty("k8s.ca.cooldown", "5");
        System.setProperty("k8s.duration", "120");

        final var s = new K8sClusterAutoscalerExample().runAndReturnSummary();

        assertTrue(s.finalNodes() > s.initialNodes(),
            "ClusterAutoscaler must grow the cluster beyond " + s.initialNodes()
                + " nodes; finalNodes=" + s.finalNodes());
        assertTrue(s.provisionedByPool() >= 1,
            "At least one node must come from the pool; got " + s.provisionedByPool());
        // The DeploymentController re-spawns pods that the scheduler initially
        // marks unschedulable, so the post-run snapshot can include extras above
        // `replicas` AND leftover unschedulables awaiting destruction. We just
        // assert the workload eventually fit (at least replicas placed).
        assertTrue(s.placedPods() >= s.replicas(),
            "At least " + s.replicas() + " pods must be placed; got " + s.placedPods());
    }
}
