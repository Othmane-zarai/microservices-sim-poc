package org.cloudsimplus.examples.kubernetes;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.KubernetesService;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.autoscaling.HorizontalPodAutoscaler;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.controllers.ReplicaSetController;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.networking.queueing.MMcQueueModel;
import org.cloudsimplus.kubernetes.networking.queueing.QueueingModel;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.kubernetes.tracing.CallGraph;
import org.cloudsimplus.kubernetes.tracing.JaegerJsonExporter;
import org.cloudsimplus.kubernetes.tracing.RequestTrace;
import org.cloudsimplus.kubernetes.tracing.RequestTraceGenerator;
import org.cloudsimplus.kubernetes.autoscaling.MetricsPipeline;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import com.example.cspsim.interactive.WorkloadShape;
import com.example.cspsim.interactive.WorkloadUtilizationModel;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.function.IntSupplier;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Simulator counterpart of the
 * <a href="https://github.com/GoogleCloudPlatform/microservices-demo">
 * Google Cloud {@code microservices-demo} (Online Boutique)</a> reference
 * workload.  The demo is a 10-microservice e-commerce application that
 * Google maintains as the canonical example of a polyglot, cloud-native
 * Kubernetes deployment.  Replaying its topology against \ckm exercises
 * pod placement, ReplicaSet management, per-service HPA control loops,
 * and inter-service call latency in a single end-to-end scenario.
 *
 * <p>The topology mirrors the manifests at
 * {@code release/kubernetes-manifests.yaml} in the upstream repo
 * (commit {@code v0.10.x}).  Resource requests/limits and replica
 * counts are taken from the manifest defaults; load patterns mirror
 * the bundled {@code loadgenerator} (Locust) service.</p>
 *
 * <p>Two execution modes are supported:</p>
 * <ul>
 *   <li><b>Steady-state</b> ({@code -Dboutique.profile=steady}, default):
 *       each service runs at its mean CPU utilisation observed under
 *       Locust at 100 concurrent users; useful for placement validation.</li>
 *   <li><b>Stress</b> ({@code -Dboutique.profile=stress}): ramps to
 *       95% CPU on the front-end and checkout services so the HPA
 *       scales them; exercises the autoscaling control loop.</li>
 *   <li><b>High</b> ({@code -Dboutique.profile=high}): matches the
 *       real-cluster USERS=500 capture; per-pod load decays via
 *       LoadSpreadingModel on frontend/checkout so the HPA traverses
 *       a non-degenerate trajectory before settling. maxReplicas=10.</li>
 * </ul>
 *
 * <p>Per-service M/M/c request latency is attached via
 * {@link MMcQueueModel} (Kleinrock 1975 §4.2): each replica is one
 * exponential server, and the per-replica service rates come from
 * {@code deployment/rq4/real-latency.csv} (μ = 1 / measured Avg-RT,
 * rounded).</p>
 *
 * <p>Knobs:</p>
 * <pre>
 *   -Dk8s.nodes=4                            worker count (default matches the OCI k3s substrate)
 *   -Dk8s.duration=600                       sim seconds (default 10 min)
 *   -Dboutique.profile=steady|moderate|stress|high   load profile
 *   -Dboutique.hpa.targetCpu=0.70            HPA target utilisation
 * </pre>
 */
public final class K8sOnlineBoutiqueExample {

    /**
     * The ten Online Boutique microservices, in the order Google's manifest
     * declares them.  Resource requests are exactly the upstream values
     * (search the upstream repo for {@code resources:} blocks).  The
     * {@code meanUtil} column is the steady-state CPU utilisation observed
     * with 100 concurrent users.  {@code serviceRate} (μ, req/s per replica)
     * is calibrated from {@code deployment/rq4/real-latency.csv}.
     */
    private enum BoutiqueService {
        FRONTEND               ("frontend",                "100m", "64Mi",  "200m", "128Mi", 1, 0.45, 304.0),
        CARTSERVICE            ("cartservice",             "200m", "64Mi",  "300m", "128Mi", 1, 0.20, 461.0),
        PRODUCTCATALOGSERVICE  ("productcatalogservice",   "100m", "64Mi",  "200m", "128Mi", 1, 0.15, 467.0),
        CURRENCYSERVICE        ("currencyservice",         "100m", "64Mi",  "200m", "128Mi", 1, 0.10, 410.0),
        PAYMENTSERVICE         ("paymentservice",          "100m", "64Mi",  "200m", "128Mi", 1, 0.10, 410.0),
        SHIPPINGSERVICE        ("shippingservice",         "100m", "64Mi",  "200m", "128Mi", 1, 0.10, 410.0),
        EMAILSERVICE           ("emailservice",            "100m", "64Mi",  "200m", "128Mi", 1, 0.05, 410.0),
        CHECKOUTSERVICE        ("checkoutservice",         "100m", "64Mi",  "200m", "128Mi", 1, 0.20, 464.0),
        RECOMMENDATIONSERVICE  ("recommendationservice",   "100m", "220Mi", "200m", "450Mi", 1, 0.15, 410.0),
        ADSERVICE              ("adservice",               "200m", "180Mi", "300m", "300Mi", 1, 0.10, 410.0),
        REDIS_CART             ("redis-cart",              "70m",  "200Mi", "125m", "256Mi", 1, 0.05, 410.0);

        final String name;
        final String cpuReq, memReq, cpuLim, memLim;
        final int replicas;
        final double meanUtil;
        /** Per-replica exponential service rate μ in req/s (calibrated from RQ4 real latency). */
        final double serviceRate;

        BoutiqueService(final String name,
                        final String cpuReq, final String memReq,
                        final String cpuLim, final String memLim,
                        final int replicas, final double meanUtil,
                        final double serviceRate) {
            this.name = name;
            this.cpuReq = cpuReq; this.memReq = memReq;
            this.cpuLim = cpuLim; this.memLim = memLim;
            this.replicas = replicas;
            this.meanUtil = meanUtil;
            this.serviceRate = serviceRate;
        }
    }

    public record Summary(int totalPods, int placedPods, int unschedulable,
                          double simEndClock, long wallClockMs) {}

    public static void main(final String[] args) {
        new K8sOnlineBoutiqueExample().runAndReturnSummary();
    }

    public Summary runAndReturnSummary() {
        // -Dk8s.nodeNames=k3s-server,k3s-worker-1,k3s-worker-2,k3s-worker-3
        // overrides the default worker-N naming so placement CSVs use real
        // node names and compare.py produces meaningful winner-agreement counts.
        final String nodeNamesProp = System.getProperty("k8s.nodeNames", "");
        final String[] explicitNames = nodeNamesProp.isBlank()
            ? new String[0] : nodeNamesProp.split(",");
        final int nodeCount  = explicitNames.length > 0
            ? explicitNames.length : intProp("k8s.nodes", 4);
        final double duration = doubleProp("k8s.duration", 600.0);
        final String profile  = System.getProperty("boutique.profile", "steady");
        final double hpaTarget = doubleProp("boutique.hpa.targetCpu", 0.70);

        // CSV emission knobs. When -Dk8s.emit*Csv=true the example writes
        // sim-placement.csv / sim-hpa.csv / sim-latency.csv under k8s.emitDir
        // (default: deployment/online-boutique/), schema-matched to
        // deployment/online-boutique/compare.py.
        final boolean emitPlacement = boolProp("k8s.emitPlacementCsv", false);
        final boolean emitHpa = boolProp("k8s.emitHpaCsv", false);
        final boolean emitLatency = boolProp("k8s.emitLatencyCsv", false);
        final boolean emitTraces = boolProp("k8s.emitTracesJson", false);
        final int tracesPerSec = intProp("k8s.tracesPerSec", 5);
        final Path outDir = Paths.get(System.getProperty(
            "k8s.emitDir", "deployment/online-boutique"));

        System.out.println("K8sOnlineBoutiqueExample (Google microservices-demo)");
        System.out.printf("  nodes=%d  profile=%s  duration=%.0fs  hpaTarget=%.2f%n",
            nodeCount, profile, duration, hpaTarget);
        if (explicitNames.length > 0) {
            System.out.printf("  nodeNames=%s%n", String.join(",", explicitNames));
        }
        System.out.printf("  emitDir=%s placement=%b hpa=%b latency=%b traces=%b@%d/s%n",
            outDir, emitPlacement, emitHpa, emitLatency, emitTraces, tracesPerSec);

        final var sim = new CloudSimPlus();
        final List<KubernetesNode> nodes = new ArrayList<>(nodeCount);
        // 4 PEs × 1000 MIPS per node mirrors the upstream microservices-demo
        // README recommendation (4-vCPU worker nodes; smaller shapes leave some
        // services Pending under K8s' integer-CPU-request quantisation, which
        // the simulator preserves as the M3 simplification documented in §7.4).
        for (int i = 0; i < nodeCount; i++) {
            final String nodeName = (i < explicitNames.length)
                ? explicitNames[i].trim() : "worker-" + (i + 1);
            nodes.add(NodeBuilder.of(nodeName)
                .pes(4, 1000).ram(6_144).rack("r" + (i + 1)).build());
        }
        new DatacenterSimple(sim, nodes,
            new KubernetesScheduler(VmAllocationPolicyTopologyAware.Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);

        // Per-service registries we need for CSV emission later.
        final Map<String, ReplicaSetController> rsByService = new HashMap<>();
        final Map<String, HorizontalPodAutoscaler> hpaByService = new HashMap<>();
        final Map<String, Integer> maxReplicasByService = new HashMap<>();
        final Map<String, MMcQueueModel> queueByService = new HashMap<>();
        final List<String[]> hpaTimeline = new ArrayList<>();

        for (final var svc : BoutiqueService.values()) {
            final double util = effectiveLoad(svc, profile);
            // Per-pod CPU spreads across replicas via WorkloadUtilizationModel:
            // per-pod = min(load(t) / replicas, 1.0). For static profiles
            // load(t)=util (the former LoadSpreadingModel); for the `high-ramp`
            // profile load(t) follows a cold-start arrival ramp (see
            // workloadShape) before settling at the same plateau.
            // Applied to frontend (moderate) so it stabilises at maxReplicas=3.
            // NOT applied to recommendationservice for moderate because at 0.85
            // total load, spreading to 2 pods gives 0.85/2=42.5% → HPA would
            // immediately scale down; instead keep 0.85 fixed so it holds at 3.
            // For `high`/`high-ramp` RECOMMENDATIONSERVICE also uses spread so it
            // plateaus at the observed real maxReplicas (=3 at USERS=500) instead
            // of saturating at maxReplicas=10.
            final boolean useSpread = isLoadSpread(svc, profile);
            final WorkloadShape shape = workloadShape(svc, profile, util);

            // RS reference captured via single-element holder so the lambda can close over it.
            @SuppressWarnings("unchecked")
            final ReplicaSetController[] rsHolder = new ReplicaSetController[1];

            final var template = new PodTemplate(ord -> {
                final IntSupplier replicas = () -> rsHolder[0] == null ? 1
                    : Math.max(1, rsHolder[0].getManagedPods().size());
                final var utilModel = useSpread
                    ? new WorkloadUtilizationModel(shape, 1.0, replicas, null, 0.0,
                        0xB0011EL + svc.ordinal())
                    : new UtilizationModelDynamic(util);
                return PodBuilder.of(svc.name + "-" + ord)
                    .label("app", svc.name)
                    .label("tier", tier(svc))
                    .container(ContainerBuilder.of(svc.name)
                        .image("gcr.io/google-samples/microservices-demo/" + svc.name + ":v0.10.0")
                        .cpu(svc.cpuLim).mem(svc.memLim)
                        .length(1_000_000)
                        .cpuUtilization(utilModel)
                        .build())
                    .build();
            });

            final var rs = new ReplicaSetController(
                broker.getControllerManager().allocateUid(),
                svc.name, Namespace.DEFAULT, template, svc.replicas);
            rsHolder[0] = rs;
            broker.addController(rs);
            rsByService.put(svc.name, rs);

            // Attach the per-deployment M/M/c latency model: c = replica
            // count, μ from RQ4 calibration, per-service deterministic seed
            // (0xB0011E + ordinal).  See Kleinrock 1975 §4.2.
            final var queue = new MMcQueueModel(svc.serviceRate, svc.replicas,
                0xB0011EL + svc.ordinal());
            queueByService.put(svc.name, queue);
            final var k8sSvc = new KubernetesService(
                svc.name, Namespace.DEFAULT,
                LabelSelector.matchLabel("app", svc.name));
            k8sSvc.setQueueingModel(queue);
            broker.addService(k8sSvc);
            System.out.printf("  mu[%s]=%.0f req/s, c=%d%n",
                svc.name, svc.serviceRate, svc.replicas);

            // Per-upstream-manifest practice: HPA is attached only to the
            // front-of-house services that experience elastic load.
            if (svc == BoutiqueService.FRONTEND || svc == BoutiqueService.CHECKOUTSERVICE
                || svc == BoutiqueService.RECOMMENDATIONSERVICE) {
                // moderate: real 200-user run observed max 3 replicas on the
                // 4-node OCI cluster (small node shapes limit headroom).
                // Keep maxReplicas=10 for stress but cap at observed 3 for moderate.
                final int maxR = "moderate".equalsIgnoreCase(profile) ? 3 : 10;
                final var hpa = HorizontalPodAutoscaler.of(rs, hpaTarget)
                    .setMinReplicas(1).setMaxReplicas(maxR);
                // For moderate profile: use a tighter pipeline (5 s scrape / 5 s
                // sync) so per-pod load-spreading updates propagate before the next
                // HPA decision, preventing artificial cascades beyond the
                // resource-limited ceiling.
                if ("moderate".equalsIgnoreCase(profile)) {
                    hpa.setPipeline(new MetricsPipeline(5.0, 5.0, 60.0));
                }
                broker.registerTick(hpa);
                hpaByService.put(svc.name, hpa);
                maxReplicasByService.put(svc.name, maxR);
            }
        }

        // 5-second HPA sampler — mirrors collect-metrics.sh's real-side cadence
        // so the two CSVs can be aligned by index.
        // k8s.hpaWarmupSeconds (default 30) delays recording until the HPA has
        // had time to fire at least once, so the first CSV row reflects
        // post-scale-up steady state rather than the pre-pipeline warm-up period.
        final double hpaWarmupSec = doubleProp("k8s.hpaWarmupSeconds", 30.0);
        if (emitHpa) {
            final double[] nextSample = {hpaWarmupSec};
            broker.registerTick((Tick) clock -> {
                if (clock < nextSample[0]) return;
                nextSample[0] = clock + 5.0;
                final Instant ts = Instant.now();
                for (final var entry : hpaByService.entrySet()) {
                    final var svcName = entry.getKey();
                    final var hpa = entry.getValue();
                    final var rs = rsByService.get(svcName);
                    final List<KubernetesPod> pods = rs.getManagedPods();
                    double cpu = 0.0;
                    int counted = 0;
                    for (final var pod : pods) {
                        if (!pod.isReady()) continue;
                        cpu += pod.getCpuPercentUtilization();
                        counted++;
                    }
                    final double avgCpuPct = counted == 0 ? 0.0
                        : (cpu / counted) * 100.0;
                    hpaTimeline.add(new String[] {
                        ts.toString(),
                        svcName,
                        Integer.toString(rs.getDesiredReplicas()),
                        Integer.toString(pods.size()),
                        String.format(Locale.ROOT, "%.0f", hpa.getTargetCpuUtilization() * 100.0),
                        String.format(Locale.ROOT, "%.0f", avgCpuPct),
                    });
                }
            });
        }

        // Request-trace generator (Phase C). Synthesises Jaeger-compatible
        // spans by walking the boutique call graph and drawing per-service
        // service times from each MMcQueueModel.
        final List<RequestTrace> traces = new ArrayList<>();
        if (emitTraces) {
            final var graph = buildBoutiqueCallGraph();
            // Provision each trace queue with c = the HPA steady-state replica
            // count of the backing Deployment (paper §8.5). For elastic services
            // we predict the plateau deterministically from the calibrated load
            // and the HPA's [minReplicas, maxReplicas] bounds, which is timing-
            // independent (so it holds for any traceWarmupSeconds) and equals the
            // live count writeLatencyCsv re-derives from rs.getDesiredReplicas()
            // at finalisation — keeping the trace-side and CSV-side latency
            // models consistent. Using c=1 would saturate elastic queues and
            // silently drop callee spans (RequestTraceGenerator skips draws
            // returning non-finite or negative durations).
            final Map<String, MMcQueueModel> traceQueues = new HashMap<>();
            for (final var svc : BoutiqueService.values()) {
                final var origQ = queueByService.get(svc.name);
                if (origQ == null) continue;
                final boolean isElastic = hpaByService.containsKey(svc.name);
                final int cPlateau;
                if (isElastic) {
                    final double load = effectiveLoad(svc, profile);
                    final int maxR = maxReplicasByService.getOrDefault(svc.name, 10);
                    if (isLoadSpread(svc, profile)) {
                        // effectiveLoad is the aggregate load across replicas:
                        // HPA equilibrium = ceil(total / target), clamped to
                        // [1, maxReplicas].
                        cPlateau = Math.min(maxR,
                            Math.max(1, (int) Math.ceil(load / hpaTarget)));
                    } else {
                        // effectiveLoad is per-pod utilisation, fixed under
                        // scale-out: the HPA runs to maxReplicas whenever per-pod
                        // load exceeds target, else stays at minReplicas.
                        // (recommendationservice/moderate: 0.85 > 0.70 -> 3,
                        // matching the maxReplicas-capped live plateau.)
                        cPlateau = load > hpaTarget ? maxR : 1;
                    }
                } else {
                    cPlateau = origQ.getServers();
                }
                traceQueues.put(svc.name, new MMcQueueModel(
                    origQ.getServiceRate(), cPlateau,
                    0xB0011EL + svc.ordinal()));
                // Worked-example diagnostic (paper §8.5): dump the exact
                // (mu, c, rho, lambda) the trace generator uses so the reported
                // calibration tuple is reproducible rather than hand-derived.
                if (svc == BoutiqueService.RECOMMENDATIONSERVICE) {
                    final double mu = origQ.getServiceRate();
                    final double rhoEff = effectiveLoad(svc, profile);
                    final double lambda = isLoadSpread(svc, profile)
                        ? rhoEff * mu
                        : rhoEff * cPlateau * mu;
                    final double rhoQueue = lambda / (cPlateau * mu);
                    System.out.printf(Locale.ROOT,
                        "WORKED-EXAMPLE[%s] profile=%s mu=%.1f c=%d lambda=%.1f rho=%.3f%n",
                        svc.name, profile, mu, cPlateau, lambda, rhoQueue);
                }
            }
            final Map<String, QueueingModel> queueMap = new HashMap<>(traceQueues);
            final RequestTraceGenerator.ArrivalRateLookup rates = name -> {
                final var q = traceQueues.get(name);
                if (q == null) return 0.0;
                final var enumSvc = lookupService(name);
                if (enumSvc == null) return 0.5 * q.getServers() * q.getServiceRate();
                final double rhoOrTotal = effectiveLoad(enumSvc, profile);
                // For elastic services under LoadSpreading (high/high-recs and
                // moderate-non-RECS), effectiveLoad is the *total* CPU load
                // summed across replicas, so per-pod rho = totalLoad / c and
                // lambda = totalLoad * mu (independent of c). For services
                // that are NOT LoadSpread (steady, stress, moderate-RECS,
                // non-elastic), effectiveLoad is per-pod rho and
                // lambda = rho * c * mu. Mirror the useSpread predicate
                // applied at queue construction (line ~234).
                return isLoadSpread(enumSvc, profile)
                    ? rhoOrTotal * q.getServiceRate()
                    : rhoOrTotal * q.getServers() * q.getServiceRate();
            };
            final var generator = new RequestTraceGenerator(
                graph, queueMap, rates, 0xCAFEBABEL);
            final double traceWarmupSec = doubleProp("k8s.traceWarmupSeconds", hpaWarmupSec + 20.0);
            final double[] nextTraceAt = {traceWarmupSec};
            broker.registerTick((Tick) clock -> {
                if (clock < traceWarmupSec) return;
                final double tickPeriod = 1.0 / Math.max(1, tracesPerSec);
                // Catch up: emit as many traces as fit between nextTraceAt and now.
                while (nextTraceAt[0] <= clock) {
                    traces.add(generator.generate(nextTraceAt[0]));
                    nextTraceAt[0] += tickPeriod;
                }
            });
        }

        sim.terminateAt(duration);
        final long t0 = System.nanoTime();
        sim.start();
        final long wallMs = (System.nanoTime() - t0) / 1_000_000L;

        final int total = broker.getPods().size();
        final int placed = (int) broker.getPods().stream()
            .filter(p -> p.getHost() != null && p.getHost().getId() >= 0).count();
        final int unsched = total - placed;

        System.out.printf("Result: %d/%d pods placed (%d unschedulable); sim clock %.2fs, wall %d ms%n",
            placed, total, unsched, sim.clock(), wallMs);

        if (emitPlacement || emitHpa || emitLatency) {
            try { Files.createDirectories(outDir); } catch (IOException ignored) {}
        }
        if (emitPlacement) {
            writePlacementCsv(outDir.resolve("sim-placement.csv"), broker.getPods());
        }
        if (emitHpa) {
            writeHpaCsv(outDir.resolve("sim-hpa.csv"), hpaTimeline);
        }
        if (emitLatency) {
            writeLatencyCsv(outDir.resolve("sim-latency.csv"), queueByService, rsByService, profile);
        }
        if (emitTraces) {
            try {
                JaegerJsonExporter.writeTo(outDir.resolve("sim-traces.json"), traces);
                System.out.println("Wrote " + outDir.resolve("sim-traces.json")
                    + " (" + traces.size() + " traces)");
            } catch (IOException e) {
                System.err.println("Failed to write traces JSON: " + e.getMessage());
            }
        }

        return new Summary(total, placed, unsched, sim.clock(), wallMs);
    }

    /**
     * Builds the Online Boutique call graph from the upstream architecture
     * diagram at
     * https://github.com/GoogleCloudPlatform/microservices-demo#architecture
     *
     * <p>Each entry path corresponds to a route the bundled
     * {@code loadgenerator} (Locust) actually exercises; weights are
     * proportional to the {@code @task(weight=N)} declarations in
     * {@code src/loadgenerator/locustfile.py} (commit v0.10.1).</p>
     */
    private static CallGraph buildBoutiqueCallGraph() {
        final var g = CallGraph.builder();

        // Helpers.
        // Each EntryPath: name, entryService, entryOperation, weight, list of edges
        // Weights mirror locust task weights (browse-heavy).

        // GET / → frontend -> productcatalog/list, currency/convert, ad/serve, recommend/list
        g.addEntry(new CallGraph.EntryPath(
            "GET /", "frontend", "HTTP GET /", 5.0,
            List.of(
                CallGraph.Edge.always("frontend", "productcatalogservice", "ListProducts"),
                CallGraph.Edge.always("frontend", "currencyservice",       "GetSupportedCurrencies"),
                CallGraph.Edge.always("frontend", "adservice",             "GetAds"),
                CallGraph.Edge.always("frontend", "recommendationservice", "ListRecommendations"),
                CallGraph.Edge.always("recommendationservice", "productcatalogservice", "GetProduct")
            )));

        // GET /product/{id}
        g.addEntry(new CallGraph.EntryPath(
            "GET /product", "frontend", "HTTP GET /product/{id}", 3.0,
            List.of(
                CallGraph.Edge.always("frontend", "productcatalogservice", "GetProduct"),
                CallGraph.Edge.always("frontend", "currencyservice",       "Convert"),
                CallGraph.Edge.always("frontend", "recommendationservice", "ListRecommendations"),
                CallGraph.Edge.always("recommendationservice", "productcatalogservice", "GetProduct"),
                CallGraph.Edge.always("frontend", "adservice",             "GetAds")
            )));

        // GET /cart
        g.addEntry(new CallGraph.EntryPath(
            "GET /cart", "frontend", "HTTP GET /cart", 2.0,
            List.of(
                CallGraph.Edge.always("frontend",    "cartservice",            "GetCart"),
                CallGraph.Edge.always("cartservice", "redis-cart",             "HGETALL"),
                CallGraph.Edge.always("frontend",    "productcatalogservice",  "GetProduct"),
                CallGraph.Edge.always("frontend",    "currencyservice",        "Convert"),
                CallGraph.Edge.always("frontend",    "recommendationservice",  "ListRecommendations"),
                CallGraph.Edge.always("frontend",    "shippingservice",        "GetQuote")
            )));

        // POST /cart — add to cart
        g.addEntry(new CallGraph.EntryPath(
            "POST /cart", "frontend", "HTTP POST /cart", 2.0,
            List.of(
                CallGraph.Edge.always("frontend",    "productcatalogservice", "GetProduct"),
                CallGraph.Edge.always("frontend",    "cartservice",           "AddItem"),
                CallGraph.Edge.always("cartservice", "redis-cart",            "HSET")
            )));

        // POST /cart/checkout
        g.addEntry(new CallGraph.EntryPath(
            "POST /checkout", "frontend", "HTTP POST /cart/checkout", 1.0,
            List.of(
                CallGraph.Edge.always("frontend",        "checkoutservice",       "PlaceOrder"),
                CallGraph.Edge.always("checkoutservice", "cartservice",           "GetCart"),
                CallGraph.Edge.always("cartservice",     "redis-cart",            "HGETALL"),
                CallGraph.Edge.always("checkoutservice", "productcatalogservice", "GetProduct"),
                CallGraph.Edge.always("checkoutservice", "currencyservice",       "Convert"),
                CallGraph.Edge.always("checkoutservice", "shippingservice",       "GetQuote"),
                CallGraph.Edge.always("checkoutservice", "shippingservice",       "ShipOrder"),
                CallGraph.Edge.always("checkoutservice", "paymentservice",        "Charge"),
                CallGraph.Edge.always("checkoutservice", "emailservice",          "SendOrderConfirmation"),
                CallGraph.Edge.always("checkoutservice", "cartservice",           "EmptyCart")
            )));

        // POST /setCurrency
        g.addEntry(new CallGraph.EntryPath(
            "POST /setCurrency", "frontend", "HTTP POST /setCurrency", 1.0,
            List.of(
                CallGraph.Edge.always("frontend", "productcatalogservice", "ListProducts"),
                CallGraph.Edge.always("frontend", "currencyservice",       "GetSupportedCurrencies")
            )));

        return g;
    }

    private static BoutiqueService lookupService(final String name) {
        for (final var s : BoutiqueService.values()) {
            if (s.name.equals(name)) return s;
        }
        return null;
    }

    private static void writePlacementCsv(final Path file,
                                          final List<KubernetesPod> pods) {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
            w.println("pod,namespace,node,phase,ip");
            for (final var pod : pods) {
                final var host = pod.getHost();
                final String node = (host instanceof KubernetesNode kn)
                    ? kn.effectiveName() : "pending";
                w.printf("%s,%s,%s,%s,%s%n",
                    pod.getPodName(),
                    pod.getNamespace().getName(),
                    node,
                    pod.getPhase(),
                    "10.0.0." + pod.getId());
            }
            System.out.println("Wrote " + file);
        } catch (IOException e) {
            System.err.println("Failed to write placement CSV: " + e.getMessage());
        }
    }

    private static void writeHpaCsv(final Path file, final List<String[]> rows) {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
            w.println("timestamp,service,desired,current,target_cpu,observed_cpu");
            for (final var r : rows) {
                w.println(String.join(",", r));
            }
            System.out.println("Wrote " + file + " (" + rows.size() + " rows)");
        } catch (IOException e) {
            System.err.println("Failed to write HPA CSV: " + e.getMessage());
        }
    }

    /**
     * Draws 1000 per-request samples from each service's M/M/c model and writes
     * p50/p95/p99 in milliseconds. Arrival rate is set so that ρ = meanUtil for
     * steady profile, or ρ = stress utilisation for elastic services, matching
     * the same load assumption the HPA acts on.
     */
    private static void writeLatencyCsv(final Path file,
                                        final Map<String, MMcQueueModel> queues,
                                        final Map<String, ReplicaSetController> rss,
                                        final String profile) {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
            w.println("method,path,p50_ms,p95_ms,p99_ms,rps");
            for (final var svc : BoutiqueService.values()) {
                final var q = queues.get(svc.name);
                if (q == null) continue;
                // Use the final post-HPA replica count so the M/M/c model
                // reflects the cluster's actual steady-state parallelism.
                // Per-pod load = totalLoad / c (consistent with LoadSpreading).
                final int cFinal = Math.max(1,
                    rss.get(svc.name).getDesiredReplicas());
                final double rho = effectiveLoad(svc, profile);
                final double totalLoad = rho * q.getServers();
                final double perPodLoad = totalLoad / cFinal;
                final double mu = q.getServiceRate();
                // Rebuild the model with the final replica count and per-pod load.
                final var qFinal = new MMcQueueModel(mu, cFinal,
                    0xB0011EL + svc.ordinal());
                final double lambda = perPodLoad * cFinal * mu;
                final int n = 1000;
                final double[] samples = new double[n];
                int finite = 0;
                for (int i = 0; i < n; i++) {
                    final double r = qFinal.draw(lambda);
                    if (Double.isFinite(r)) samples[finite++] = r;
                }
                if (finite == 0) {
                    w.printf("GET,/%s,0.000,0.000,0.000,%.2f%n", svc.name, lambda);
                    continue;
                }
                final double[] sub = new double[finite];
                System.arraycopy(samples, 0, sub, 0, finite);
                java.util.Arrays.sort(sub);
                final double p50 = sub[(int) (finite * 0.50)] * 1000.0;
                final double p95 = sub[(int) (finite * 0.95)] * 1000.0;
                final double p99 = sub[(int) Math.min(finite - 1, finite * 0.99)] * 1000.0;
                w.printf(Locale.ROOT, "GET,/%s,%.3f,%.3f,%.3f,%.2f%n",
                    svc.name, p50, p95, p99, lambda);
            }
            System.out.println("Wrote " + file);
        } catch (IOException e) {
            System.err.println("Failed to write latency CSV: " + e.getMessage());
        }
    }

    /**
     * The per-service load curve fed to {@link WorkloadUtilizationModel}. For
     * every profile except {@code high-ramp} this is a constant at the
     * {@link #effectiveLoad} value (reproducing the former {@code
     * LoadSpreadingModel}). For {@code high-ramp} the three elastic services
     * follow a cold-start arrival ramp from a low baseline up to the calibrated
     * USERS=500 plateau over the first 180&nbsp;s, then hold — modelling the
     * gradual Locust ramp-up instead of a step load. Because the ramp settles
     * at the same plateau, the HPA equilibrium is unchanged (6/3/1).
     */
    private static WorkloadShape workloadShape(final BoutiqueService svc,
                                               final String profile, final double load) {
        final boolean elastic = svc == BoutiqueService.FRONTEND
            || svc == BoutiqueService.CHECKOUTSERVICE
            || svc == BoutiqueService.RECOMMENDATIONSERVICE;
        if ("high-ramp".equalsIgnoreCase(profile) && elastic) {
            return WorkloadShape.rampUp(0.10, load, 0.0, 180.0);
        }
        return WorkloadShape.constant(load);
    }

    /**
     * Whether {@code svc}'s calibrated load is applied as an aggregate
     * (LoadSpreading) value to be divided across replicas, versus a fixed
     * per-pod utilisation. Elastic services (frontend, checkoutservice,
     * recommendationservice) spread under {@code high*} profiles and under
     * {@code moderate}/{@code stress} for all but recommendationservice, whose
     * moderate load is held per-pod so it plateaus at maxReplicas. Used at
     * queue construction, trace-queue sizing, and arrival-rate lookup so the
     * three sites cannot drift apart.
     */
    private static boolean isLoadSpread(final BoutiqueService svc, final String profile) {
        final boolean elastic = svc == BoutiqueService.FRONTEND
            || svc == BoutiqueService.CHECKOUTSERVICE
            || svc == BoutiqueService.RECOMMENDATIONSERVICE;
        return elastic
            && ((svc != BoutiqueService.RECOMMENDATIONSERVICE
                    && ("moderate".equalsIgnoreCase(profile) || "stress".equalsIgnoreCase(profile)))
                || "high".equalsIgnoreCase(profile)
                || "high-ramp".equalsIgnoreCase(profile)
                || "high-recs".equalsIgnoreCase(profile));
    }

    private static double effectiveLoad(final BoutiqueService svc, final String profile) {
        return switch (profile.toLowerCase(Locale.ROOT)) {
            case "stress" -> switch (svc) {
                case FRONTEND, CHECKOUTSERVICE, RECOMMENDATIONSERVICE -> 0.95;
                default -> svc.meanUtil;
            };
            // "moderate": matches the real 200-user load observation (2026-05-21).
            // frontend ~78% (LoadSpreading → stabilises at maxReplicas=3).
            // recommendationservice was observed at 91-111% CPU (throttled past
            // 100% limit on small OCI nodes): use 0.85 WITHOUT LoadSpreading so
            // the sim holds at maxReplicas=3 exactly like the real cluster.
            // checkoutservice ~6% — stays at minReplicas=1.
            case "moderate" -> switch (svc) {
                case FRONTEND                -> 0.78;
                case RECOMMENDATIONSERVICE  -> 0.85;
                case CHECKOUTSERVICE        -> 0.06;
                default -> svc.meanUtil;
            };
            // "high": calibrated against the real USERS=500 capture.
            // Real-cluster observed steady-state: frontend=6 replicas,
            // recommendationservice=3, checkoutservice=1. With LoadSpreading
            // on all three elastic services and HPA target=0.70, the plateau
            // satisfies totalLoad / N_plateau <= 0.70 < totalLoad / (N_plateau-1).
            // frontend N=6: totalLoad in (3.5, 4.2] -> pick 3.8.
            // recommendationservice N=3: totalLoad in (1.4, 2.1] -> pick 1.8.
            // checkoutservice stays at 1: totalLoad < 0.70.
            // "high-ramp": identical plateau loads to "high"; the only
            // difference is the per-pod load arrives via a time-varying
            // ramp (see workloadShape) rather than as a step at t=0, so the
            // steady-state equilibrium is by construction the same 6/3/1.
            case "high", "high-ramp" -> switch (svc) {
                case FRONTEND                -> 3.80;
                case CHECKOUTSERVICE         -> 0.40;
                case RECOMMENDATIONSERVICE   -> 1.80;
                default -> svc.meanUtil;
            };
            // "high-recs": calibrated against the USERS=500 capture using
            // the recommendation-heavy Locust task mix in
            // deployment/online-boutique/locustfile-recs.py (index + browseProduct
            // both call ListRecommendations and account for 40 of 47 weight
            // units). Real-cluster observed steady-state in that mix:
            // frontend=6, recommendationservice=6, checkoutservice=1.
            // recs N=6: totalLoad in (3.5, 4.2] -> pick 3.80 (matched to frontend).
            case "high-recs" -> switch (svc) {
                case FRONTEND                -> 3.80;
                case CHECKOUTSERVICE         -> 0.40;
                case RECOMMENDATIONSERVICE   -> 3.80;
                default -> svc.meanUtil;
            };
            default -> svc.meanUtil; // "steady"
        };
    }

    /**
     * Three-tier classification (front / business / data) that mirrors the
     * upstream Helm-chart labels and lets the scheduler apply different
     * anti-affinity policies per tier in extensions.
     */
    private static String tier(final BoutiqueService svc) {
        return switch (svc) {
            case FRONTEND -> "front";
            case REDIS_CART -> "data";
            default -> "business";
        };
    }

    private static int intProp(final String key, final int def) {
        final String v = System.getProperty(key);
        return v == null ? def : Integer.parseInt(v);
    }

    private static double doubleProp(final String key, final double def) {
        final String v = System.getProperty(key);
        return v == null ? def : Double.parseDouble(v);
    }

    private static boolean boolProp(final String key, final boolean def) {
        final String v = System.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }
}
