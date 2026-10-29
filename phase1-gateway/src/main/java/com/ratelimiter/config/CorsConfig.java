package com.ratelimiter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the Phase 4 React dashboard (served from a different port by Vite,
 * e.g. localhost:5173) to call this gateway's /admin/** endpoints from the
 * browser. Scoped to /admin/** only and localhost origins -- this is a
 * local benchmarking tool, not a public-facing API, so a permissive CORS
 * policy here is a deliberate, scoped choice, not an oversight. Do not
 * widen this if you ever deploy the gateway itself publicly.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/admin/**")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("GET", "POST")
                .allowedHeaders("*");
    }
}
