package org.cloudsimplus.examples.kubernetes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sCronJobExampleTest {

    @BeforeEach @AfterEach
    void clearProps() {
        System.clearProperty("k8s.nodes");
        System.clearProperty("k8s.cron.schedule");
        System.clearProperty("k8s.cron.policy");
        System.clearProperty("k8s.cron.completions");
        System.clearProperty("k8s.cron.taskLengthMI");
        System.clearProperty("k8s.duration");
    }

    @Test
    void firesExpectedNumberOfTimesUnderAllowPolicy() {
        // 120s duration / 20s schedule = 6 expected firings under ALLOW.
        System.setProperty("k8s.nodes", "2");
        System.setProperty("k8s.cron.schedule", "20");
        System.setProperty("k8s.cron.policy", "ALLOW");
        System.setProperty("k8s.cron.taskLengthMI", "5000");
        System.setProperty("k8s.duration", "120");

        final var s = new K8sCronJobExample().runAndReturnSummary();

        assertEquals("ALLOW", s.concurrencyPolicy());
        assertTrue(s.firedCount() >= s.expectedFires(),
            "ALLOW policy must fire at least " + s.expectedFires()
                + " times, got " + s.firedCount());
    }

    @Test
    void shortDurationProducesAtLeastOneFiring() {
        System.setProperty("k8s.nodes", "2");
        System.setProperty("k8s.cron.schedule", "10");
        System.setProperty("k8s.cron.taskLengthMI", "2000");
        System.setProperty("k8s.duration", "30");

        final var s = new K8sCronJobExample().runAndReturnSummary();

        assertTrue(s.firedCount() >= 1,
            "Expected at least one CronJob firing within 30s @ schedule=10s");
    }
}
