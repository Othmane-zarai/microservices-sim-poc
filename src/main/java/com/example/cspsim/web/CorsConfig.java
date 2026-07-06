package com.example.cspsim.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the standalone Vite frontend (a different origin in dev) to call the
 * API. Origins come from {@code cspsim.cors.allowed-origins} (comma-separated).
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${cspsim.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins)
            .allowedMethods("GET", "POST", "DELETE", "OPTIONS");
    }
}
