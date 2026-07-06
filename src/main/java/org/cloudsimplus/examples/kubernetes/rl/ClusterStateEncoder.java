package org.cloudsimplus.examples.kubernetes.rl;

import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.vms.Vm;

import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;

/**
 * Discretises the cluster into a compact, hashable state for tabular
 * Q-learning.
 *
 * <p>Each Kubernetes node contributes one digit: the number of pods currently
 * placed on it, capped at {@link #maxPerNode}. The full state is the per-node
 * count vector in a stable node order (lexicographic by node name), so the
 * same physical situation always maps to the same state key across runs and
 * JVMs.</p>
 *
 * <p>Pod-count digits (rather than coarse load buckets) are used on purpose:
 * every placement changes exactly one digit, so the greedy policy can always
 * tell which node it just loaded. A load-bucket encoding aliases the first few
 * placements into one state and makes the greedy policy pile pods onto a
 * single node — see {@link RewardFunction#loadBalance()} for the load metric
 * the reward still uses.</p>
 *
 * <p>The action space is the index into this same ordered node list, which is
 * why both the encoder and {@link QLearningScheduler} delegate ordering here.</p>
 */
public final class ClusterStateEncoder {

    /** Per-node pod-count cap; counts at or above this share one state digit. */
    private final int maxPerNode;

    public ClusterStateEncoder(final int maxPerNode) {
        if (maxPerNode < 1) {
            throw new IllegalArgumentException("maxPerNode must be >= 1, got " + maxPerNode);
        }
        this.maxPerNode = maxPerNode;
    }

    public int maxPerNode() {
        return maxPerNode;
    }

    /**
     * The Kubernetes nodes among {@code hosts}, in the canonical order used for
     * both state digits and action indices.
     */
    public List<KubernetesNode> orderedNodes(final List<? extends Host> hosts) {
        return hosts.stream()
            .filter(KubernetesNode.class::isInstance)
            .map(KubernetesNode.class::cast)
            .sorted(Comparator.comparing(KubernetesNode::getNodeName))
            .toList();
    }

    /** Number of pods currently placed on a node. */
    public static int podCount(final Host host) {
        return (int) host.getVmList().stream()
            .filter(KubernetesPod.class::isInstance)
            .count();
    }

    /** Allocation load of a host in [0, 1]: reserved MIPS over total capacity. */
    public static double loadFraction(final Host host) {
        final double total = host.getTotalMipsCapacity();
        if (total <= 0) {
            return 0.0;
        }
        final double reserved = host.getVmList().stream()
            .mapToDouble(Vm::getTotalMipsCapacity)
            .sum();
        return Math.min(1.0, reserved / total);
    }

    /** State digit for one node: its capped pod count. */
    public int digit(final Host host) {
        return Math.min(maxPerNode, podCount(host));
    }

    /** Encode the current cluster into a stable state key, e.g. {@code "0,2,1,3"}. */
    public String encode(final List<? extends Host> hosts) {
        final var key = new StringJoiner(",");
        for (final KubernetesNode node : orderedNodes(hosts)) {
            key.add(Integer.toString(digit(node)));
        }
        return key.toString();
    }
}
