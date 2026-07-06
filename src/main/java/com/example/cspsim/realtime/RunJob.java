package com.example.cspsim.realtime;

/**
 * A unit of streaming work (run one example, or one interactive simulation).
 * Executed on the {@link RunRegistry}'s worker thread once the client opens the
 * SSE stream. The job emits events through the supplied {@link RunHandle}; the
 * registry sends the terminal {@code done}/{@code error} event and closes the
 * emitter afterward.
 */
@FunctionalInterface
public interface RunJob {
    void run(RunHandle handle) throws Exception;
}
