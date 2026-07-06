package com.example.cspsim.simulation;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Discovers every CloudSim Plus example class on the classpath under
 * org.cloudsimplus.examples (excluding the networks sub-package, which
 * was intentionally left out of this Spring Boot port) plus any
 * project-specific examples placed under com.example.cspsim.examples.
 *
 * Examples are matched by either having a static {@code main(String[])}
 * method or an instance/static no-arg {@code main()} entry-point (the
 * Java 25 unnamed-class style used by a couple of the sources).
 *
 * Uses Spring's resource resolver so it works inside an executable
 * Spring Boot fat jar (nested:// protocol), in an exploded layout, and
 * inside an IDE.
 */
@Component
public class ExampleRegistry {

    private static final List<String> BASE_PACKAGES = List.of(
        "org.cloudsimplus.examples",
        "com.example.cspsim.examples"
    );
    private static final String EXCLUDED_SUBPACKAGE = "org.cloudsimplus.examples.networks";

    private final Map<String, Class<?>> examplesBySimpleName = new TreeMap<>();
    private final Map<String, Class<?>> examplesByFqn = new TreeMap<>();

    @PostConstruct
    void scan() throws IOException {
        final var resolver = new PathMatchingResourcePatternResolver();
        final MetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);

        for (final String basePackage : BASE_PACKAGES) {
            final var pattern = "classpath*:" + basePackage.replace('.', '/') + "/**/*.class";
            final Resource[] resources = resolver.getResources(pattern);

            for (Resource res : resources) {
                if (!res.isReadable()) continue;
                final MetadataReader reader;
                try {
                    reader = factory.getMetadataReader(res);
                } catch (IOException ignored) {
                    continue;
                }
                final var className = reader.getClassMetadata().getClassName();
                if (className.contains("$")) continue;
                if (className.startsWith(EXCLUDED_SUBPACKAGE)) continue;
                if (className.endsWith(".package-info")) continue;

                final Class<?> cls;
                try {
                    cls = ClassUtils.forName(className, Thread.currentThread().getContextClassLoader());
                } catch (Throwable t) {
                    continue;
                }
                if (!hasEntryPoint(cls)) continue;

                examplesByFqn.put(className, cls);
                examplesBySimpleName.putIfAbsent(cls.getSimpleName(), cls);
            }
        }
    }

    /** A discovered example for the API: simple name, FQN, and its subpackage group. */
    public record ExampleInfo(String name, String fullyQualifiedName, String group) {}

    /** All examples as {@link ExampleInfo}, sorted by group then name (for the UI). */
    public List<ExampleInfo> list() {
        return examplesByFqn.entrySet().stream()
            .map(e -> new ExampleInfo(e.getValue().getSimpleName(), e.getKey(), groupOf(e.getKey())))
            .sorted(Comparator.comparing(ExampleInfo::group).thenComparing(ExampleInfo::name))
            .toList();
    }

    private static String groupOf(String fqn) {
        final String base = "org.cloudsimplus.examples.";
        if (fqn.startsWith(base)) {
            final String rest = fqn.substring(base.length());
            final int dot = rest.indexOf('.');
            return dot > 0 ? rest.substring(0, dot) : "(root)";
        }
        return "other";
    }

    public Collection<String> listSimpleNames() {
        return Collections.unmodifiableSet(examplesBySimpleName.keySet());
    }

    public Collection<String> listFullyQualifiedNames() {
        return Collections.unmodifiableSet(examplesByFqn.keySet());
    }

    public Optional<Class<?>> resolve(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        final var byFqn = examplesByFqn.get(name);
        if (byFqn != null) return Optional.of(byFqn);
        return Optional.ofNullable(examplesBySimpleName.get(name));
    }

    private static boolean hasEntryPoint(Class<?> cls) {
        for (var m : cls.getDeclaredMethods()) {
            if (!"main".equals(m.getName())) continue;
            if (m.getReturnType() != void.class) continue;
            final var params = m.getParameterTypes();
            if (params.length == 0) return true;
            if (params.length == 1 && params[0] == String[].class) return true;
        }
        return false;
    }
}
