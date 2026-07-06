package com.example.cspsim;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the web API over a real port: example discovery and the
 * interactive-simulation SSE stream (tick snapshots + summary).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void listsDiscoveredExamples() throws Exception {
        final var resp = http.send(
            HttpRequest.newBuilder(URI.create(base() + "/api/examples")).GET().build(),
            BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("K8sHPAExample"),
            "example list should include K8sHPAExample");
    }

    @Test
    void streamsInteractiveSimulationTicksAndSummary() throws Exception {
        final String spec = """
            {
              "nodeCount": 2,
              "durationSeconds": 10,
              "tickIntervalSeconds": 1,
              "throttleMillis": 0,
              "deployments": [
                {"name":"web","replicas":2,"cpu":"500m","mem":"256Mi","cpuUtilization":0.8,
                 "requestsPerSecond":20,"serviceRatePerReplica":30}
              ],
              "autoscalers": [
                {"kind":"HPA","target":"web","minReplicas":1,"maxReplicas":4,"cpuTarget":0.5,"cooldownSeconds":5}
              ]
            }
            """;

        final var post = http.send(
            HttpRequest.newBuilder(URI.create(base() + "/api/simulations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(spec)).build(),
            BodyHandlers.ofString());
        assertEquals(200, post.statusCode());

        final String runId = post.body().replaceAll(".*\"runId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        assertTrue(runId.length() > 10, "expected a runId, got: " + post.body());

        // The stream completes when the (fast) simulation ends.
        final var stream = http.send(
            HttpRequest.newBuilder(URI.create(base() + "/api/simulations/" + runId + "/stream")).GET().build(),
            BodyHandlers.ofString());
        final String body = stream.body();
        assertTrue(body.contains("event:tick"), "expected tick events in:\n" + body);
        assertTrue(body.contains("event:summary"), "expected a summary event");
    }
}
