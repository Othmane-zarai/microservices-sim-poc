package com.example.cspsim.simulation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * Invokes a CloudSim Plus example by its simple name (e.g. "BasicFirstExample")
 * or its fully qualified name. Captures stdout/stderr produced during the run
 * so it can be returned to the caller.
 * The original examples expose either:
 *   - a static main(String[]) method (the classic style), or
 *   - an instance main() / static main() (Java 25 instance-main style).
 * Both are supported here.
 */
@Service
public class SimulationRunnerService {

    private static final Logger log = LoggerFactory.getLogger(SimulationRunnerService.class);

    private final ExampleRegistry registry;

    public SimulationRunnerService(ExampleRegistry registry) {
        this.registry = registry;
    }

    public RunResult run(String name) {
        final var cls = registry.resolve(name)
            .orElseThrow(() -> new NoSuchElementException("Unknown example: " + name));

        log.info("Running CloudSim Plus example {}", cls.getName());

        final var capturedOut = new ByteArrayOutputStream();
        final var capturedErr = new ByteArrayOutputStream();
        final var originalOut = System.out;
        final var originalErr = System.err;

        final long start = System.nanoTime();
        boolean success = true;
        String error = null;

        try (PrintStream pOut = new PrintStream(capturedOut, true, StandardCharsets.UTF_8);
             PrintStream pErr = new PrintStream(capturedErr, true, StandardCharsets.UTF_8)) {
            System.setOut(new TeePrintStream(originalOut, pOut));
            System.setErr(new TeePrintStream(originalErr, pErr));
            invokeEntryPoint(cls);
        } catch (Throwable t) {
            success = false;
            error = unwrap(t).toString();
            log.error("Example {} failed", cls.getSimpleName(), t);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        final String stdout = capturedOut.toString(StandardCharsets.UTF_8);

        return new RunResult(
            cls.getSimpleName(),
            cls.getName(),
            success,
            elapsedMs,
            stdout,
            capturedErr.toString(StandardCharsets.UTF_8),
            error
        );
    }

    /** Serializes the global System.out / {@code -D} mutation across streaming runs. */
    private static final Object GLOBAL_RUN_LOCK = new Object();

    /**
     * Runs an example while forwarding its stdout/stderr to {@code onLine} one
     * line at a time, so a caller (the SSE layer) can stream output live. The
     * supplied {@code props} are applied as JVM system properties for the
     * duration of the run (the examples read their knobs via {@code -D}) and
     * restored afterward. Because both system properties and {@code System.out}
     * are JVM-global, the body holds {@link #GLOBAL_RUN_LOCK} — callers must not
     * run two examples concurrently.
     */
    public RunResult runStreaming(String name, Map<String, String> props, Consumer<String> onLine) {
        final var cls = registry.resolve(name)
            .orElseThrow(() -> new NoSuchElementException("Unknown example: " + name));

        synchronized (GLOBAL_RUN_LOCK) {
            log.info("Streaming CloudSim Plus example {}", cls.getName());
            final Map<String, String> previousProps = applyProps(props);
            final var originalOut = System.out;
            final var originalErr = System.err;
            final long start = System.nanoTime();
            boolean success = true;
            String error = null;

            try (PrintStream lineOut = new PrintStream(
                    new LineForwardingOutputStream(onLine), true, StandardCharsets.UTF_8)) {
                System.setOut(new TeePrintStream(originalOut, lineOut));
                System.setErr(new TeePrintStream(originalErr, lineOut));
                invokeEntryPoint(cls);
            } catch (Throwable t) {
                success = false;
                error = unwrap(t).toString();
                log.error("Example {} failed", cls.getSimpleName(), t);
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
                restoreProps(previousProps);
            }

            final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            return new RunResult(cls.getSimpleName(), cls.getName(), success, elapsedMs, "", "", error);
        }
    }

    /** Apply props, returning the prior values (null value ⇒ property was unset). */
    private static Map<String, String> applyProps(Map<String, String> props) {
        final Map<String, String> previous = new HashMap<>();
        if (props == null) return previous;
        props.forEach((k, v) -> {
            if (k == null || k.isBlank()) return;
            previous.put(k, System.getProperty(k));
            if (v == null) System.clearProperty(k);
            else System.setProperty(k, v);
        });
        return previous;
    }

    private static void restoreProps(Map<String, String> previous) {
        previous.forEach((k, v) -> {
            if (v == null) System.clearProperty(k);
            else System.setProperty(k, v);
        });
    }

    private static void invokeEntryPoint(Class<?> cls)
            throws ReflectiveOperationException {

        // Prefer classic static main(String[])
        final var staticMainArgs = findMain(cls, true, new Class<?>[]{String[].class});
        if (staticMainArgs != null) {
            staticMainArgs.setAccessible(true);
            staticMainArgs.invoke(null, (Object) new String[0]);
            return;
        }
        // static main()
        final var staticMainNoArgs = findMain(cls, true, new Class<?>[]{});
        if (staticMainNoArgs != null) {
            staticMainNoArgs.setAccessible(true);
            staticMainNoArgs.invoke(null);
            return;
        }
        // instance main(String[]) - rare, but handle it
        final var instanceMainArgs = findMain(cls, false, new Class<?>[]{String[].class});
        if (instanceMainArgs != null) {
            instanceMainArgs.setAccessible(true);
            instanceMainArgs.invoke(newInstance(cls), (Object) new String[0]);
            return;
        }
        // instance main()
        final var instanceMainNoArgs = findMain(cls, false, new Class<?>[]{});
        if (instanceMainNoArgs != null) {
            instanceMainNoArgs.setAccessible(true);
            instanceMainNoArgs.invoke(newInstance(cls));
            return;
        }
        throw new IllegalStateException("No usable main() entry-point on " + cls.getName());
    }

    private static Method findMain(Class<?> cls, boolean isStatic, Class<?>[] params) {
        try {
            final var m = cls.getDeclaredMethod("main", params);
            return Modifier.isStatic(m.getModifiers()) == isStatic ? m : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object newInstance(Class<?> cls) throws ReflectiveOperationException {
        Constructor<?> ctor;
        try {
            ctor = cls.getDeclaredConstructor();
        } catch (NoSuchMethodException nsme) {
            throw new IllegalStateException(
                "Class " + cls.getName() + " has no no-arg constructor; cannot invoke instance main()");
        }
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof InvocationTargetException ite && ite.getCause() != null) ? ite.getCause() : t;
    }

    public record RunResult(
        String name,
        String fullyQualifiedName,
        boolean success,
        long elapsedMs,
        String stdout,
        String stderr,
        String error
    ) {}

    /**
     * Mirrors writes to two streams so the original console output is preserved
     * while we also accumulate a copy for the HTTP response body.
     */
    private static final class TeePrintStream extends PrintStream {
        private final PrintStream other;
        TeePrintStream(PrintStream primary, PrintStream other) {
            super(primary, true, StandardCharsets.UTF_8);
            this.other = other;
        }
        @Override public void write(int b) { super.write(b); other.write(b); }
        @Override public void write(byte[] buf, int off, int len) {
            super.write(buf, off, len);
            other.write(buf, off, len);
        }
        @Override public void flush() { super.flush(); other.flush(); }
    }

    /**
     * Accumulates bytes and invokes {@code onLine} once per completed line
     * (split on '\n', '\r' stripped), so example output streams to the client
     * line-by-line instead of as one blob at the end.
     */
    private static final class LineForwardingOutputStream extends OutputStream {
        private final Consumer<String> onLine;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(256);

        LineForwardingOutputStream(Consumer<String> onLine) {
            this.onLine = onLine;
        }

        @Override public synchronized void write(int b) {
            if (b == '\n') flushLine();
            else if (b != '\r') buf.write(b);
        }

        @Override public synchronized void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) write(b[off + i]);
        }

        @Override public synchronized void flush() {
            if (buf.size() > 0) flushLine();
        }

        private void flushLine() {
            final String line = buf.toString(StandardCharsets.UTF_8);
            buf.reset();
            try {
                onLine.accept(line);
            } catch (RuntimeException ignored) {
                // a slow/closed consumer must not break the simulation
            }
        }
    }
}
