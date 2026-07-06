package org.cloudsimplus.examples.kubernetes.rl;

import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.vms.Vm;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * A {@link KubernetesScheduler} whose pod-placement decisions are made by a
 * tabular Q-learning agent.
 *
 * <h2>Decision loop</h2>
 * <p>For each pod, {@link #defaultFindHostForVm(Vm)}:</p>
 * <ol>
 *   <li>Encodes the current cluster into a state {@code s} and computes the
 *       feasible node indices (strict K8s filters + capacity).</li>
 *   <li>Before committing, applies a deferred TD(0) update for the
 *       <i>previous</i> decision, using {@code s} as its observed next-state
 *       and the reward measured now (the previous placement's effect):
 *       {@code Q(s_prev, a_prev) += α·(r + γ·maxₐ Q(s, a) − Q(s_prev, a_prev))}.</li>
 *   <li>Chooses an action (node) for {@code s} via epsilon-greedy over the
 *       feasible indices and returns that node.</li>
 * </ol>
 *
 * <p>When no Kubernetes node is feasible the scheduler defers to the parent's
 * {@link #defaultFindHostForVm(Vm)} so unschedulable-marking and
 * priority-preemption semantics are preserved (the Cluster Autoscaler relies
 * on the unschedulable signal). Non-{@link KubernetesPod} VMs also fall
 * through to the parent.</p>
 *
 * <p>Call {@link #beginEpisode()} before each simulation run that shares this
 * agent's {@link QTable}, and {@link #endEpisode(double)} once it finishes to
 * flush the final transition with a measured terminal reward.</p>
 *
 * <p><b>Determinism:</b> exploration draws from a seeded {@link Random}; a
 * fixed seed reproduces the placement timeline exactly. See the determinism
 * contract on {@link KubernetesScheduler}.</p>
 */
public final class QLearningScheduler extends KubernetesScheduler {

    private final QTable q;
    private final ClusterStateEncoder encoder;
    private final RewardFunction reward;
    private final double alpha;
    private final double gamma;
    private final double epsilon;
    private final Random rng;

    // Deferred transition: the decision awaiting its observed next-state.
    private boolean hasPending;
    private String pendingState;
    private int pendingAction;

    public QLearningScheduler(final QTable q,
                              final ClusterStateEncoder encoder,
                              final RewardFunction reward,
                              final double alpha,
                              final double gamma,
                              final double epsilon,
                              final long seed) {
        // The parent Policy is irrelevant — we override placement wholesale —
        // but the enum constructor requires a value.
        super(Policy.COST_OPTIMIZED);
        this.q = q;
        this.encoder = encoder;
        this.reward = reward;
        this.alpha = alpha;
        this.gamma = gamma;
        this.epsilon = epsilon;
        this.rng = new Random(seed);
    }

    /** Clear any carry-over transition before a fresh episode. */
    public void beginEpisode() {
        hasPending = false;
        pendingState = null;
        pendingAction = -1;
    }

    /**
     * Flush the last pending transition with a measured terminal reward (no
     * bootstrap), closing out the episode's learning. The caller supplies the
     * reward observed at the final loaded state (e.g. from the last controller
     * tick) because the cluster's pods are torn down once the run ends.
     */
    public void endEpisode(final double terminalReward) {
        if (hasPending) {
            q.update(pendingState, pendingAction, terminalReward, alpha);
            hasPending = false;
        }
    }

    @Override
    protected Optional<Host> defaultFindHostForVm(final Vm vm) {
        if (!(vm instanceof KubernetesPod pod)) {
            return super.defaultFindHostForVm(vm);
        }

        final List<Host> hosts = this.<Host>getHostList();
        final List<KubernetesNode> ordered = encoder.orderedNodes(hosts);

        final List<Integer> feasible = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            final KubernetesNode node = ordered.get(i);
            if (passesStrictConstraints(pod, node) && node.getSuitabilityFor(pod).fully()) {
                feasible.add(i);
            }
        }

        // No K8s node fits: hand back to the parent so the unschedulable signal
        // and preemption path still run.
        if (feasible.isEmpty()) {
            return super.defaultFindHostForVm(vm);
        }

        final String state = encoder.encode(hosts);

        // Deferred TD(0): reward the previous placement using the state it led
        // to (the current one) and bootstrap off this state's best feasible Q.
        if (hasPending) {
            final double r = reward.reward(ordered);
            final double target = r + gamma * q.maxQ(state, feasible);
            q.update(pendingState, pendingAction, target, alpha);
        }

        final int action = q.chooseEpsilonGreedy(state, feasible, epsilon, rng);

        pendingState = state;
        pendingAction = action;
        hasPending = true;

        return Optional.of(ordered.get(action));
    }
}
