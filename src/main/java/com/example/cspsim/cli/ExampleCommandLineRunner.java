package com.example.cspsim.cli;

import com.example.cspsim.simulation.ExampleRegistry;
import com.example.cspsim.simulation.SimulationRunnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Allows running an example from the command line, e.g.:
 *
 *   java -jar app.jar --example=K8sHPAExample
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments=--example=K8sClusterExample
 *
 * Use --list to print every discovered example. With no recognized option the
 * application context starts and then exits (no web server is started).
 */
@Component
public class ExampleCommandLineRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExampleCommandLineRunner.class);

    private final ExampleRegistry registry;
    private final SimulationRunnerService runner;

    public ExampleCommandLineRunner(ExampleRegistry registry, SimulationRunnerService runner) {
        this.registry = registry;
        this.runner = runner;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption("list")) {
            log.info("Available CloudSim Plus examples:");
            registry.listSimpleNames().forEach(name -> log.info("  - {}", name));
            return;
        }
        if (!args.containsOption("example")) return;

        final var values = args.getOptionValues("example");
        if (values == null || values.isEmpty()) {
            log.warn("--example was given without a value; nothing to run");
            return;
        }
        final var name = values.get(0);
        final var result = runner.run(name);
        log.info("Example {} finished in {} ms (success={})",
            result.name(), result.elapsedMs(), result.success());
        if (!result.success()) {
            log.error("Error: {}", result.error());
        }
        // Exit the JVM so CLI callers (pipes, sweeps) receive EOF without
        // waiting for the embedded Tomcat server to be stopped externally.
        System.exit(result.success() ? 0 : 1);
    }
}
