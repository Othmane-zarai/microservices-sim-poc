package org.cloudsimplus.examples.kubernetes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sCustomSchedulerExampleTest {

    @BeforeEach @AfterEach
    void clearProps() {
        System.clearProperty("k8s.nodes");
        System.clearProperty("k8s.replicas");
        System.clearProperty("k8s.duration");
    }

    /**
     * 4 nodes × 4 PE × 1000 MIPS = 4000 MIPS each. Each pod requests 500m =
     * 500 MIPS. So one node fits 8 pods of 500 MIPS — bin-packing should put
     * ALL replicas onto worker-1 (the lexicographically smallest, which the
     * tie-break selects when every node is equally empty at first).
     *
     * <p>Once worker-1 has any pod, its free MIPS drops below worker-2/3/4 and
     * remains the winner until full. The next pod then spills to worker-2.
     */
    @Test
    void binPackingPacksReplicasOntoFewestHosts() {
        System.setProperty("k8s.nodes", "4");
        System.setProperty("k8s.replicas", "8");
        System.setProperty("k8s.duration", "60");

        final var s = new K8sCustomSchedulerExample().runAndReturnSummary();

        assertEquals(4, s.nodeCount());
        assertEquals(8, s.replicas());
        assertTrue(s.placedPodCount() >= 8,
            "All 8 replicas should place (got " + s.placedPodCount() + ")");
        // Headline assertion: bin-packing keeps the host count below the
        // node count. Cost-optimized would spread to all 4 racks (different
        // costPerHour); bin-packing collapses onto 1 (since all 8 pods of
        // 500 MIPS fit in one 4000 MIPS host).
        assertEquals(1, s.hostsUsed(),
            "Bin-packing should pack all 8 pods onto a single host (got "
                + s.hostsUsed() + ")");
    }

    @Test
    void binPackingSpillsToSecondHostWhenFirstSaturates() {
        // 12 replicas × 500 MIPS = 6000 MIPS — one 4000-MIPS node fills, the
        // rest must spill to a second node.
        System.setProperty("k8s.nodes", "4");
        System.setProperty("k8s.replicas", "12");
        System.setProperty("k8s.duration", "60");

        final var s = new K8sCustomSchedulerExample().runAndReturnSummary();

        assertEquals(2, s.hostsUsed(),
            "12 pods of 500m should saturate one host and spill to a second (got "
                + s.hostsUsed() + ")");
    }
}
