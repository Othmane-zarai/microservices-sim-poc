package org.cloudsimplus.examples.kubernetes.rl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A tabular {@code Q(state, action)} store backing {@link QLearningScheduler}.
 *
 * <p>States are the string keys produced by {@link ClusterStateEncoder};
 * actions are node indices in {@code [0, actionCount)}. Unseen states default
 * to an all-zero action-value row, so optimistic/zero initialisation is the
 * implicit prior.</p>
 *
 * <p>Not thread-safe — a single simulation drives it sequentially.</p>
 */
public final class QTable {

    private final int actionCount;
    private final Map<String, double[]> table = new HashMap<>();

    public QTable(final int actionCount) {
        if (actionCount < 1) {
            throw new IllegalArgumentException("actionCount must be >= 1");
        }
        this.actionCount = actionCount;
    }

    public int actionCount() {
        return actionCount;
    }

    /** Number of distinct states visited so far (learning-progress diagnostic). */
    public int statesSeen() {
        return table.size();
    }

    private double[] row(final String state) {
        return table.computeIfAbsent(state, s -> new double[actionCount]);
    }

    public double q(final String state, final int action) {
        return row(state)[action];
    }

    /** Max action-value over a set of currently-feasible actions; 0 if none. */
    public double maxQ(final String state, final List<Integer> feasibleActions) {
        final double[] r = row(state);
        double best = Double.NEGATIVE_INFINITY;
        for (final int a : feasibleActions) {
            best = Math.max(best, r[a]);
        }
        return best == Double.NEGATIVE_INFINITY ? 0.0 : best;
    }

    /**
     * Epsilon-greedy action choice restricted to {@code feasibleActions}.
     * Ties on the greedy path are broken by lowest action index, so behaviour
     * is deterministic for a fixed {@code rng} seed.
     */
    public int chooseEpsilonGreedy(final String state,
                                   final List<Integer> feasibleActions,
                                   final double epsilon,
                                   final Random rng) {
        if (feasibleActions.isEmpty()) {
            throw new IllegalArgumentException("no feasible actions for state " + state);
        }
        if (rng.nextDouble() < epsilon) {
            return feasibleActions.get(rng.nextInt(feasibleActions.size()));
        }
        return greedy(state, feasibleActions);
    }

    /** Greedy action over feasible actions; lowest index wins ties. */
    public int greedy(final String state, final List<Integer> feasibleActions) {
        final double[] r = row(state);
        int bestAction = feasibleActions.get(0);
        double bestValue = r[bestAction];
        for (final int a : feasibleActions) {
            if (r[a] > bestValue) {
                bestValue = r[a];
                bestAction = a;
            }
        }
        return bestAction;
    }

    /**
     * TD(0) update: {@code Q(s,a) += alpha * (target - Q(s,a))}, where
     * {@code target = reward + gamma * maxQ(s')} (or just {@code reward} at a
     * terminal step).
     */
    public void update(final String state, final int action,
                       final double target, final double alpha) {
        final double[] r = row(state);
        r[action] += alpha * (target - r[action]);
    }
}
