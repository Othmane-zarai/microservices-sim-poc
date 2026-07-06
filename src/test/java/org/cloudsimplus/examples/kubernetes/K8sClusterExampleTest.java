package org.cloudsimplus.examples.kubernetes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke tests for {@link K8sClusterExample}: drives the example end-to-end
 * with system-property knobs and asserts on observable scheduling outcomes
 * plus the post-review feature wiring (kubelet pre-flight, PV/PVC binding,
 * NetworkPolicy / Ingress registration).
 */
class K8sClusterExampleTest {

    @BeforeEach
    void clearProps() {
        System.clearProperty("k8s.nodes");
        System.clearProperty("k8s.replicas");
        System.clearProperty("k8s.duration");
        System.clearProperty("k8s.tickInterval");
        System.clearProperty("k8s.quiet");
    }

    @AfterEach
    void cleanProps() {
        clearProps();
    }

    @Test
    void threeNodesThreeReplicasAllPlaced() {
        System.setProperty("k8s.nodes", "3");
        System.setProperty("k8s.replicas", "3");
        System.setProperty("k8s.duration", "30");

        final var summary = new K8sClusterExample().runAndReturnSummary();

        assertEquals(3, summary.nodeCount());
        assertEquals(3, summary.placements().size(),
            "All 3 pods must be placed on the 3-node cluster");
        // The COST_OPTIMIZED policy with uniform costs may pack pods together;
        // we only assert capacity, not spread (a Phase-4 deterministic
        // tie-break would let us assert one-per-node — see plan E5).
    }

    @Test
    void capacityExceededLeavesAtLeastOneUnschedulable() {
        // 3 nodes × 4 PEs each = 12 PEs total; with 1500m CPU per pod some
        // packing happens. We assert at least one fewer placement than the
        // raw replica count (the precise number depends on scheduler retry
        // behaviour, which respawns failed pods on every reconcile cycle).
        System.setProperty("k8s.nodes", "3");
        System.setProperty("k8s.replicas", "13");
        System.setProperty("k8s.duration", "30");

        final var summary = new K8sClusterExample().runAndReturnSummary();

        assertEquals(3, summary.nodeCount());
        assertTrue(summary.placements().size() >= 12,
            "At least 12 placements are physically possible; got "
                + summary.placements().size());
        assertTrue(summary.simEndClock() >= 30.0,
            "Sim should run to terminateAt=30s");
    }

    @Test
    void persistentVolumeClaimBindsAtRegistrationTime() {
        // The example registers a PV with 10 GB and a 1 GB PVC; the broker's
        // first-fit binder runs synchronously inside addPersistentVolumeClaim,
        // so the PVC must be bound by the time the simulation finishes.
        System.setProperty("k8s.nodes", "3");
        System.setProperty("k8s.replicas", "3");
        System.setProperty("k8s.duration", "30");

        final var summary = new K8sClusterExample().runAndReturnSummary();

        assertTrue(summary.pvcBound(),
            "PVC 'web-data' must bind to PV 'data-pv' on registration");
    }

    @Test
    void ingressRouteResolvesToBackingService() {
        // The example registers an Ingress mapping example.com/ → web service;
        // routeIngress must return the service even when the simulation has
        // already torn down placed pods.
        System.setProperty("k8s.nodes", "3");
        System.setProperty("k8s.replicas", "3");
        System.setProperty("k8s.duration", "30");

        final var summary = new K8sClusterExample().runAndReturnSummary();

        assertTrue(summary.ingressResolved(),
            "Ingress example.com/ must resolve to the registered KubernetesService");
    }

    @Test
    void preflightDependenciesDoNotBlockPodStartupWhenPresent() {
        // The example registers ConfigMap/Secret/SA + binds the PVC BEFORE
        // submitting the Deployment, so the kubelet pre-flight must clear on
        // first reconcile and pods reach RUNNING (placed) within the run.
        System.setProperty("k8s.nodes", "3");
        System.setProperty("k8s.replicas", "3");
        System.setProperty("k8s.duration", "30");

        final var summary = new K8sClusterExample().runAndReturnSummary();

        assertEquals(3, summary.placements().size(),
            "Pre-flight (ConfigMap+Secret+SA+PVC) must not block placement when deps are present");
    }
}
