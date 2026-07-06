package com.example.cspsim.web;

import com.example.cspsim.interactive.InteractiveSimulationService;
import com.example.cspsim.interactive.SimulationSpec;
import com.example.cspsim.realtime.RunRegistry;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * REST + SSE surface for the parameterized "interactive" simulation: post a
 * {@link SimulationSpec}, open the stream to run it, receive per-tick
 * {@code ClusterSnapshot} events + a final {@code summary}, cancel via DELETE.
 */
@RestController
@RequestMapping("/api")
public class InteractiveController {

    private final InteractiveSimulationService simulations;
    private final RunRegistry runs;

    public InteractiveController(InteractiveSimulationService simulations, RunRegistry runs) {
        this.simulations = simulations;
        this.runs = runs;
    }

    public record StartResponse(String runId) {}

    @PostMapping("/simulations")
    public StartResponse start(@RequestBody SimulationSpec spec) {
        if (spec == null || spec.deployments() == null || spec.deployments().isEmpty()) {
            throw new IllegalArgumentException("At least one deployment is required");
        }
        final String runId = runs.register(handle -> simulations.runTo(spec, handle));
        return new StartResponse(runId);
    }

    @GetMapping("/simulations/{runId}/stream")
    public SseEmitter stream(@PathVariable String runId) {
        return runs.attachAndRun(runId);
    }

    @DeleteMapping("/simulations/{runId}")
    public Map<String, Object> cancel(@PathVariable String runId) {
        return Map.of("cancelled", runs.cancel(runId));
    }
}
