package org.cloudsimplus.examples.kubernetes.rl;

import ch.qos.logback.classic.Level;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.DeploymentController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.lifecycle.Tick;

import java.util.ArrayList;
import java.util.List;

/**
 * Trains a {@link QLearningScheduler} to place pods so that node load is
 * balanced, then evaluates the learned greedy policy.
 *
 * <p>Each <i>episode</i> is a fresh simulation of the same workload (a single
 * Deployment of {@code replicas} pods over {@code nodes} worker nodes). The
 * shared {@link QTable} persists across episodes, so the agent improves with
 * experience while {@code epsilon} (exploration) decays. A final greedy
 * episode ({@code epsilon = 0}) measures the learned policy; we expect its
 * load imbalance to be no worse than the first, mostly-random episode.</p>
 *
 * <p>Knobs (JVM system properties):</p>
 * <pre>
 *   -Drl.nodes=4        worker nodes (4 PE × 1000 MIPS, 8 GiB each)
 *   -Drl.replicas=12    Deployment replica count
 *   -Drl.episodes=150   training episodes before the greedy evaluation
 *   -Drl.cap=replicas   per-node pod-count cap in the state encoding
 *   -Drl.duration=30    terminateAt() in simulated seconds
 *   -Drl.alpha=0.3      learning rate
 *   -Drl.gamma=0.9      discount factor
 *   -Drl.epsilon=0.30   initial exploration rate (decays ×0.92 per episode)
 *   -Drl.seed=42        base RNG seed (episode e uses seed+e)
 * </pre>
 */
public class K8sQLearningSchedulerExample {

    public record Summary(int nodeCount, int replicas, int episodes,
                          double firstImbalance, double greedyImbalance,
                          int statesSeen, boolean improved) {}

    private static final double EPSILON_DECAY = 0.92;
    /** Exploration never fully stops, so late episodes keep covering tied states. */
    private static final double EPSILON_MIN = 0.05;

    public static void main(String[] args) {
        new K8sQLearningSchedulerExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        suppressSimLogs();

        final int nodeCount = intProp("rl.nodes", 4);
        final int replicas  = intProp("rl.replicas", 12);
        final int episodes  = intProp("rl.episodes", 150);
        final int cap       = intProp("rl.cap", replicas);
        final double dur     = doubleProp("rl.duration", 30.0);
        final double alpha   = doubleProp("rl.alpha", 0.3);
        final double gamma   = doubleProp("rl.gamma", 0.9);
        final double eps0    = doubleProp("rl.epsilon", 0.30);
        final long seed      = (long) doubleProp("rl.seed", 42);

        log("╔══════════════════════════════════════════════════════════╗");
        log("║   K8s Q-Learning Scheduler  —  learn to balance load     ║");
        log("╚══════════════════════════════════════════════════════════╝");
        log("  Goal : A tabular Q-learning agent learns pod placement that");
        log("         minimises per-node load imbalance over %d episodes.", episodes);
        log("  Knobs: nodes=%d replicas=%d cap=%d α=%.2f γ=%.2f ε₀=%.2f",
            nodeCount, replicas, cap, alpha, gamma, eps0);
        log("");

        // Shared learning state across episodes.
        final var qtable  = new QTable(nodeCount);
        final var encoder = new ClusterStateEncoder(cap);
        final var reward  = RewardFunction.loadBalance();

        double firstImbalance = Double.NaN;
        log("  %-8s %-9s %-11s %s", "EPISODE", "EPSILON", "IMBALANCE", "STATES");
        log("  ─────────────────────────────────────────────────────────");
        for (int ep = 0; ep < episodes; ep++) {
            final double epsilon = Math.max(EPSILON_MIN, eps0 * Math.pow(EPSILON_DECAY, ep));
            final double imbalance = runEpisode(nodeCount, replicas, dur,
                qtable, encoder, reward, alpha, gamma, epsilon, seed + ep);
            if (ep == 0) {
                firstImbalance = imbalance;
            }
            if (ep < 5 || ep % 10 == 0 || ep == episodes - 1) {
                log("  %-8d %-9.3f %-11.4f %d", ep, epsilon, imbalance, qtable.statesSeen());
            }
        }

        // Greedy evaluation: no exploration, no further learning effect we rely on.
        final double greedyImbalance = runEpisode(nodeCount, replicas, dur,
            qtable, encoder, reward, alpha, gamma, 0.0, seed + episodes);

        final boolean improved = greedyImbalance <= firstImbalance + 1e-9;

        log("  ─────────────────────────────────────────────────────────");
        log("  First episode imbalance : %.4f  (ε=%.2f, mostly random)", firstImbalance, eps0);
        log("  Greedy policy imbalance : %.4f  (ε=0)", greedyImbalance);
        log("  Distinct states learned : %d", qtable.statesSeen());
        log("");
        if (improved) {
            log("✅ VALIDATION PASSED: learned greedy policy is at least as balanced "
                + "as the initial random policy (%.4f ≤ %.4f).", greedyImbalance, firstImbalance);
        } else {
            log("⚠️  VALIDATION SOFT-FAIL: greedy imbalance %.4f exceeded the first "
                + "episode's %.4f — try more episodes (-Drl.episodes) or a higher α.",
                greedyImbalance, firstImbalance);
        }

        return new Summary(nodeCount, replicas, episodes,
            firstImbalance, greedyImbalance, qtable.statesSeen(), improved);
    }

    /** Runs one episode and returns the final-tick load imbalance (std-dev). */
    private double runEpisode(final int nodeCount, final int replicas, final double dur,
                              final QTable qtable, final ClusterStateEncoder encoder,
                              final RewardFunction reward, final double alpha,
                              final double gamma, final double epsilon, final long seed) {
        final var sim = new CloudSimPlus();
        // Each new CloudSimPlus resets the logger level, so re-suppress per episode.
        suppressSimLogs();

        final var nodes = new ArrayList<KubernetesNode>(nodeCount);
        for (int i = 1; i <= nodeCount; i++) {
            nodes.add(NodeBuilder.of("worker-" + i)
                .pes(4, 1000).ram(8_192).rack("r" + i).build());
        }

        final var scheduler = new QLearningScheduler(
            qtable, encoder, reward, alpha, gamma, epsilon, seed);
        scheduler.beginEpisode();
        new DatacenterSimple(sim, nodes, scheduler);

        final var broker = new KubernetesClusterBroker(sim)
            .setControllerTickIntervalSeconds(1.0);

        final var template = new PodTemplate(ord -> PodBuilder.of("web-" + ord)
            .label("app", "web")
            .container(ContainerBuilder.of("nginx")
                .image("nginx:1.21")
                .cpu("500m").mem("256Mi")
                .length(50_000)
                .build())
            .build());

        final var deployment = new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "web", Namespace.DEFAULT, template, replicas);
        broker.addController(deployment);

        // Snapshot the reward at each tick; the last value is the terminal
        // reward (the cluster is torn down once the run returns).
        final double[] lastReward = { 0.0 };
        broker.registerTick((Tick) clock -> {
            final boolean anyPlaced = nodes.stream()
                .anyMatch(n -> !broker.placedPodsOnNode(n).isEmpty());
            if (anyPlaced) {
                lastReward[0] = reward.reward(nodes);
            }
        });

        sim.terminateAt(dur);
        sim.start();

        scheduler.endEpisode(lastReward[0]);
        return -lastReward[0]; // loadBalance reward = -stddev, so imbalance = -reward
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void suppressSimLogs() {
        // CloudSim Plus names its loggers by simple class name (e.g. "DatacenterSimple"),
        // not under "org.cloudsimplus", so only the ROOT level reliably silences the
        // per-event chatter. Applied per episode in case a fresh CloudSimPlus resets it.
        try {
            org.cloudsimplus.util.Log.setLevel(Level.WARN);
        } catch (IllegalArgumentException ignored) {}
    }

    private static void log(String fmt, Object... args) {
        if (args.length == 0) System.out.println(fmt);
        else System.out.printf(fmt + "%n", args);
    }

    private static int intProp(String key, int def) {
        final var v = System.getProperty(key);
        return v == null ? def : Integer.parseInt(v);
    }

    private static double doubleProp(String key, double def) {
        final var v = System.getProperty(key);
        return v == null ? def : Double.parseDouble(v);
    }
}
