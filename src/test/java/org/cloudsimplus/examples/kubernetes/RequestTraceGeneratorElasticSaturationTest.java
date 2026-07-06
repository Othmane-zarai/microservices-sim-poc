package org.cloudsimplus.examples.kubernetes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the eighth hardening-pass defect: the
 * {@code RequestTraceGenerator}'s queue map was initialised with
 * {@code c = svc.replicas} (= 1) so under {@code effectiveLoad > 1}
 * elastic services saturated immediately ({@code rho_queue >= 1}),
 * causing {@code generate()} to silently skip every callee span via
 * the {@code !Double.isFinite(rt) || rt < 0 -> continue} guard.
 * <p>
 * Concretely: under {@code -Dboutique.profile=high} the
 * {@code recommendationservice} arrival rate is
 * {@code rho = 1.80}, and {@code ListRecommendations} should appear
 * in the emitted {@code sim-traces.json} once the queue map is built
 * with the HPA equilibrium {@code c = ceil(rho / hpaTarget)} and the
 * arrival rate uses the LoadSpread-aware total-load formula.
 */
class RequestTraceGeneratorElasticSaturationTest {

    private Path tmp;

    @BeforeEach
    void setupTmp() throws IOException {
        tmp = Files.createTempDirectory("ob-trace-test-");
    }

    @AfterEach
    void clearProps() throws IOException {
        Stream.of(
            "boutique.profile", "k8s.duration", "k8s.emitDir",
            "k8s.emitTracesJson", "k8s.tracesPerSec", "k8s.nodes",
            "k8s.hpaWarmupSeconds", "k8s.traceWarmupSeconds"
        ).forEach(System::clearProperty);
        if (tmp != null) {
            try (var s = Files.walk(tmp)) {
                s.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
    }

    @Test
    void listRecommendationsSpansEmittedUnderHighProfile() throws IOException {
        System.setProperty("boutique.profile", "high");
        System.setProperty("k8s.duration", "200");
        System.setProperty("k8s.emitDir", tmp.toString());
        System.setProperty("k8s.emitTracesJson", "true");
        System.setProperty("k8s.tracesPerSec", "20");
        System.setProperty("k8s.hpaWarmupSeconds", "30");
        System.setProperty("k8s.traceWarmupSeconds", "60");

        new K8sOnlineBoutiqueExample().runAndReturnSummary();

        final Path traces = tmp.resolve("sim-traces.json");
        assertTrue(Files.exists(traces),
            "sim-traces.json should be emitted under -Dk8s.emitTracesJson=true");

        final String body = Files.readString(traces);
        final long listRecCount = countOperation(body, "ListRecommendations");
        assertTrue(listRecCount > 0,
            "RequestTraceGenerator must emit ListRecommendations spans under "
            + "boutique.profile=high (saturating effectiveLoad=1.80). The trace "
            + "queue map must be built with the HPA equilibrium c, not "
            + "svc.replicas (= 1). Got " + listRecCount + " spans.");
    }

    private static long countOperation(final String body, final String op) {
        final var m = Pattern.compile("\"operationName\":\"" + Pattern.quote(op) + "\"")
            .matcher(body);
        long n = 0;
        while (m.find()) n++;
        return n;
    }
}
