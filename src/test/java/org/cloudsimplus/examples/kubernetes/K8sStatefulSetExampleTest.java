package org.cloudsimplus.examples.kubernetes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sStatefulSetExampleTest {

    @BeforeEach @AfterEach
    void clearProps() {
        System.clearProperty("k8s.nodes");
        System.clearProperty("k8s.replicas");
        System.clearProperty("k8s.duration");
    }

    @Test
    void statefulSetUsesStableOrdinalNames() {
        System.setProperty("k8s.nodes", "5");
        System.setProperty("k8s.replicas", "3");
        System.setProperty("k8s.duration", "120");

        final var s = new K8sStatefulSetExample().runAndReturnSummary();

        // Initial pod names: db-0, db-1, db-2 (ordinal-suffixed, stable identities).
        assertEquals(List.of("db-0", "db-1", "db-2"), s.initialPodNames(),
            "StatefulSet must allocate stable, ordinal-suffixed pod names");
        // Pod-name discipline is the core invariant: every name follows the
        // db-<int> pattern (regardless of whether final scale-up landed in time).
        assertTrue(s.finalPodNames().stream().allMatch(n -> n.matches("db-\\d+")),
            "All StatefulSet pod names must follow db-<ordinal> pattern, got "
                + s.finalPodNames());
    }
}
