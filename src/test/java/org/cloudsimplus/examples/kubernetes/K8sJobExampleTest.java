package org.cloudsimplus.examples.kubernetes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sJobExampleTest {

    @BeforeEach @AfterEach
    void clearProps() {
        System.clearProperty("k8s.nodes");
        System.clearProperty("k8s.completions");
        System.clearProperty("k8s.parallelism");
        System.clearProperty("k8s.duration");
    }

    @Test
    void jobRunsToCompletionUnderRoomyCluster() {
        System.setProperty("k8s.nodes", "3");
        System.setProperty("k8s.completions", "5");
        System.setProperty("k8s.parallelism", "2");
        System.setProperty("k8s.duration", "120");

        final var s = new K8sJobExample().runAndReturnSummary();

        assertTrue(s.complete(), "Job should complete within 120s");
        assertEquals(5, s.succeeded(), "Job must reach exactly completions=5 successes");
        assertTrue(s.failures() <= s.backoffLimit(),
            "Failures must stay within backoffLimit (got " + s.failures() + ")");
    }
}
