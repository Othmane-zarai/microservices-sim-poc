package com.example.cspsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class CspSimApplication {

    /**
     * Two modes share one jar:
     * <ul>
     *   <li><b>Server</b> (no recognized CLI arg): boots Tomcat and serves the
     *       REST/SSE API for the React UI.</li>
     *   <li><b>CLI</b> ({@code --list} / {@code --example=...}): runs headless —
     *       no web server — so a one-shot example run can't fail on a port
     *       conflict and exits cleanly when done.</li>
     * </ul>
     */
    public static void main(String[] args) {
        final boolean cli = Arrays.stream(args)
            .anyMatch(a -> a.equals("--list")
                        || a.equals("--example")
                        || a.startsWith("--example="));

        final var app = new SpringApplication(CspSimApplication.class);
        if (cli) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        app.run(args);
    }
}
