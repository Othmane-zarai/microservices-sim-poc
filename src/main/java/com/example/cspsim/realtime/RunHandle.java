package com.example.cspsim.realtime;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * Per-run state shared between the worker thread executing a {@link RunJob} and
 * the HTTP request holding the SSE connection. Carries the emitter, a
 * cooperative cancellation flag, and an optional cancel action (e.g.
 * {@code sim::terminate}) the job registers so a DELETE can stop it mid-run.
 */
public final class RunHandle {

    private final String runId;
    private final SseEmitter emitter;
    private volatile boolean cancelled;
    private volatile Runnable onCancel = () -> {};

    RunHandle(String runId, SseEmitter emitter) {
        this.runId = runId;
        this.emitter = emitter;
    }

    public String runId() {
        return runId;
    }

    SseEmitter emitter() {
        return emitter;
    }

    /** Send a named SSE event; {@code data} is serialized to JSON by Jackson. */
    public void send(String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Register the action that stops the underlying work (idempotent caller). */
    public void onCancel(Runnable action) {
        this.onCancel = action == null ? () -> {} : action;
    }

    /** Mark cancelled and trigger the registered stop action. */
    void cancel() {
        this.cancelled = true;
        try {
            onCancel.run();
        } catch (RuntimeException ignored) {
            // best-effort stop; the job's cancellation check handles the rest
        }
    }
}
