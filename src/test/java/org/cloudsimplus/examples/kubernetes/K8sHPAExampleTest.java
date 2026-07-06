package org.cloudsimplus.examples.kubernetes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sHPAExampleTest {

    @BeforeEach @AfterEach
    void clearProps() {
        System.clearProperty("k8s.nodes");
        System.clearProperty("k8s.replicas");
        System.clearProperty("k8s.hpa.target");
        System.clearProperty("k8s.hpa.min");
        System.clearProperty("k8s.hpa.max");
        System.clearProperty("k8s.hpa.cooldown");
        System.clearProperty("k8s.duration");
    }

    @Test
    void hpaScalesUpUnderEightyPercentLoad() {
        System.setProperty("k8s.nodes", "4");
        System.setProperty("k8s.replicas", "2");
        System.setProperty("k8s.hpa.target", "0.5");
        System.setProperty("k8s.hpa.min", "1");
        System.setProperty("k8s.hpa.max", "8");
        System.setProperty("k8s.hpa.cooldown", "5");
        System.setProperty("k8s.duration", "60");

        final var s = new K8sHPAExample().runAndReturnSummary();

        // Expectation: 80% load at target 50% drives ceil(2*0.8/0.5)=4 in one
        // step, then ceil(4*0.8/0.5)=7 → eventually clamps at hpaMax=8.
        assertTrue(s.desiredReplicas() > s.initReplicas(),
            "HPA must scale up under 80% load (initial=" + s.initReplicas()
                + ", desired=" + s.desiredReplicas() + ")");
        assertTrue(s.desiredReplicas() <= s.hpaMax(),
            "HPA must respect maxReplicas (got " + s.desiredReplicas() + ", max=" + s.hpaMax() + ")");
    }
}
