package com.example.cspsim.realtime;

/**
 * Terminal SSE payload sent (as the {@code done} or {@code error} event) when a
 * run finishes, is cancelled, or fails. {@code summary} is the run-specific
 * result object (an example {@code RunResult} or an interactive summary), or
 * null on error/cancel.
 */
public record RunCompleted(boolean success, boolean cancelled,
                           long elapsedMs, String error, Object summary) {

    public static RunCompleted ok(long elapsedMs, Object summary) {
        return new RunCompleted(true, false, elapsedMs, null, summary);
    }

    public static RunCompleted cancelled(long elapsedMs) {
        return new RunCompleted(false, true, elapsedMs, null, null);
    }

    public static RunCompleted failed(long elapsedMs, String error) {
        return new RunCompleted(false, false, elapsedMs, error, null);
    }
}
