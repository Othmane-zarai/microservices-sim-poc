package com.example.cspsim.realtime;

/** A single line of captured stdout streamed as a {@code log} SSE event. */
public record LogLine(long seq, String line) {}
