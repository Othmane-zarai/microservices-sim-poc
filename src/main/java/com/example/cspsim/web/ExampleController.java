package com.example.cspsim.web;

import com.example.cspsim.realtime.LogLine;
import com.example.cspsim.realtime.RunRegistry;
import com.example.cspsim.simulation.ExampleRegistry;
import com.example.cspsim.simulation.SimulationRunnerService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * REST + SSE surface for the existing CloudSim Plus examples:
 * list them, start a run (with {@code -D} knobs), stream its stdout live, cancel.
 */
@RestController
@RequestMapping("/api")
public class ExampleController {

    private final ExampleRegistry registry;
    private final SimulationRunnerService runner;
    private final RunRegistry runs;

    public ExampleController(ExampleRegistry registry,
                             SimulationRunnerService runner,
                             RunRegistry runs) {
        this.registry = registry;
        this.runner = runner;
        this.runs = runs;
    }

    public record StartRunRequest(Map<String, String> props) {}
    public record StartRunResponse(String runId) {}

    @GetMapping("/examples")
    public List<ExampleRegistry.ExampleInfo> examples() {
        return registry.list();
    }

    /** Stage a run; the client then opens {@code /api/runs/{runId}/stream} to start it. */
    @PostMapping("/examples/{name}/runs")
    public StartRunResponse start(@PathVariable String name,
                                  @RequestBody(required = false) StartRunRequest body) {
        if (registry.resolve(name).isEmpty()) {
            throw new NoSuchElementException("Unknown example: " + name);
        }
        final Map<String, String> props =
            (body == null || body.props() == null) ? Map.of() : body.props();

        final String runId = runs.register(handle -> {
            final long[] seq = {0};
            final var result = runner.runStreaming(name, props, line -> {
                if (handle.isCancelled()) return;            // stop emitting; bg run finishes harmlessly
                try {
                    handle.send("log", new LogLine(seq[0]++, line));
                } catch (IOException ignored) {
                    // client disconnected — keep the sim running, just stop sending
                }
            });
            if (!handle.isCancelled()) {
                try { handle.send("summary", result); } catch (IOException ignored) {}
            }
        });
        return new StartRunResponse(runId);
    }

    @GetMapping("/runs/{runId}/stream")
    public SseEmitter stream(@PathVariable String runId) {
        return runs.attachAndRun(runId);
    }

    @DeleteMapping("/runs/{runId}")
    public Map<String, Object> cancel(@PathVariable String runId) {
        return Map.of("cancelled", runs.cancel(runId));
    }
}
