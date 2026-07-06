package org.cloudsimplus.examples.kubernetes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class K8sReplicaSetExampleTest {

    @BeforeEach @AfterEach
    void clearProps() {
        System.clearProperty("k8s.nodes");
        System.clearProperty("k8s.replicas");
        System.clearProperty("k8s.duration");
    }

    @Test
    void replicaSetScalesUpThenDownToTargets() {
        // initial=3 → up=5 (at t=20s, settled by 35s) → down=2 (at t=50s, settled by 65s)
        System.setProperty("k8s.nodes", "4");
        System.setProperty("k8s.replicas", "3");
        System.setProperty("k8s.duration", "80");

        final var s = new K8sReplicaSetExample().runAndReturnSummary();

        assertEquals(3, s.initialReplicas());
        assertEquals(5, s.afterScaleUp(),   "ReplicaSet must reconcile to 5 after scale-up");
        assertEquals(2, s.afterScaleDown(), "ReplicaSet must reconcile to 2 after scale-down");
        assertEquals(2, s.finalReplicas(),  "Final managed pods must equal last desired count");
    }
}
