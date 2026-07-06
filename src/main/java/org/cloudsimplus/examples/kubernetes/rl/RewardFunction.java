package org.cloudsimplus.examples.kubernetes.rl;

import org.cloudsimplus.kubernetes.KubernetesNode;

import java.util.List;

/**
 * Scalar reward signal evaluated over the current node states, used by
 * {@link QLearningScheduler} to drive learning. Higher is better.
 */
@FunctionalInterface
public interface RewardFunction {

    /** Reward for the cluster's current state. Higher values are preferred. */
    double reward(List<KubernetesNode> nodes);

    /**
     * Load-balancing reward: the negative population standard deviation of
     * per-node allocation load. It is maximised (→ 0) when every node carries
     * an equal share, so the agent learns to spread pods — the opposite of
     * bin-packing.
     */
    static RewardFunction loadBalance() {
        return nodes -> {
            if (nodes.isEmpty()) {
                return 0.0;
            }
            final double[] loads = nodes.stream()
                .mapToDouble(ClusterStateEncoder::loadFraction)
                .toArray();
            double mean = 0.0;
            for (final double l : loads) {
                mean += l;
            }
            mean /= loads.length;
            double variance = 0.0;
            for (final double l : loads) {
                variance += (l - mean) * (l - mean);
            }
            variance /= loads.length;
            return -Math.sqrt(variance);
        };
    }
}
