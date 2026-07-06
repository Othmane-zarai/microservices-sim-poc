/**
 * Reinforcement-learning scheduler experiments for the CloudSim Plus-K8s
 * extension.
 *
 * <p>This package is a <b>research scaffold</b>: it plugs a tabular
 * Q-learning agent into the Kubernetes pod-placement loop by subclassing
 * {@link org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler}. The
 * agent learns a placement policy that minimises a configurable reward
 * (load imbalance by default) over repeated episodes against a fixed
 * workload.</p>
 *
 * <h2>Pieces</h2>
 * <ul>
 *   <li>{@link org.cloudsimplus.examples.kubernetes.rl.ClusterStateEncoder}
 *       — discretises per-node load into a compact integer state.</li>
 *   <li>{@link org.cloudsimplus.examples.kubernetes.rl.QTable}
 *       — tabular Q(s, a) store with epsilon-greedy action selection.</li>
 *   <li>{@link org.cloudsimplus.examples.kubernetes.rl.RewardFunction}
 *       — pluggable reward over the current node states.</li>
 *   <li>{@link org.cloudsimplus.examples.kubernetes.rl.QLearningScheduler}
 *       — owns the placement decision and applies online TD(0) updates.</li>
 *   <li>{@link org.cloudsimplus.examples.kubernetes.rl.K8sQLearningSchedulerExample}
 *       — runnable episode loop; auto-discovered by the CLI runner.</li>
 * </ul>
 *
 * <h2>Scope &amp; limits</h2>
 * <p>Tabular Q-learning only scales to small clusters: the joint state space
 * is {@code levels^nodeCount}. For deep RL (DQN) on larger clusters, the
 * intended path is to log {@code (state, action, reward, next_state)}
 * transitions from {@link org.cloudsimplus.examples.kubernetes.rl.QLearningScheduler}
 * to CSV, train a policy off-line in Python, export it to ONNX, and run
 * inference back inside the scheduler. See {@code ROADMAP.md}.</p>
 */
package org.cloudsimplus.examples.kubernetes.rl;
