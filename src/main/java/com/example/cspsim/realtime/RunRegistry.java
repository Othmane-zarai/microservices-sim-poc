package com.example.cspsim.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tracks streaming runs and executes them on a single worker thread.
 *
 * <p>Flow: a controller {@link #register(RunJob)}s a job (returns a runId) when
 * the client POSTs to start; the client then opens the SSE stream, which calls
 * {@link #attachAndRun(String)} — only then does the job execute, emitting to
 * the just-connected emitter. Running the job at stream-open time avoids the
 * race where a fast simulation finishes before the browser connects.</p>
 *
 * <p>Runs are <b>serialized</b> on one thread on purpose: examples redirect the
 * global {@code System.out} and read JVM-global {@code -D} properties, so
 * concurrent runs would interfere. This is an explicit MVP constraint.</p>
 */
@Component
public class RunRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunRegistry.class);
    private static final long SSE_TIMEOUT_MS = 3_600_000L; // 1h; async timeout also disabled in properties

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        final var t = new Thread(r, "sim-runner");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, RunJob> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RunHandle> active = new ConcurrentHashMap<>();

    /** Stage a job and return its runId; the client opens the stream to start it. */
    public String register(RunJob job) {
        final String runId = UUID.randomUUID().toString();
        pending.put(runId, job);
        return runId;
    }

    /** Connect the SSE stream for a staged run and begin executing it. */
    public SseEmitter attachAndRun(String runId) {
        final RunJob job = pending.remove(runId);
        if (job == null) {
            throw new NoSuchElementException("Unknown or already-started run: " + runId);
        }
        final var emitter = new SseEmitter(SSE_TIMEOUT_MS);
        final var handle = new RunHandle(runId, emitter);
        active.put(runId, handle);

        emitter.onCompletion(() -> active.remove(runId));
        emitter.onError(e -> { handle.cancel(); active.remove(runId); });
        emitter.onTimeout(() -> { handle.cancel(); emitter.complete(); });

        executor.submit(() -> execute(handle, job));
        return emitter;
    }

    /** Cancel a run whether it is staged (not yet streamed) or actively running. */
    public boolean cancel(String runId) {
        final RunHandle h = active.get(runId);
        if (h != null) {
            h.cancel();
            return true;
        }
        return pending.remove(runId) != null;
    }

    private void execute(RunHandle handle, RunJob job) {
        final long t0 = System.nanoTime();
        try {
            job.run(handle);
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            handle.send("done", handle.isCancelled()
                ? RunCompleted.cancelled(ms)
                : RunCompleted.ok(ms, null));
            handle.emitter().complete();
        } catch (Exception e) {
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            log.warn("Run {} failed", handle.runId(), e);
            final var cause = e.getCause() != null ? e.getCause() : e;
            try {
                // "failed" (not "error") so the browser EventSource's native
                // 'error' handler — fired on normal stream close — isn't confused
                // with an application failure.
                handle.send("failed", RunCompleted.failed(ms, String.valueOf(cause)));
            } catch (IOException ignored) {
                // client already gone
            }
            handle.emitter().complete();
        } finally {
            active.remove(handle.runId());
        }
    }
}
