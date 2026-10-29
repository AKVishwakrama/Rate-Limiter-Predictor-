package com.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Adaptive Rate Limiter Gateway.
 *
 * This service exposes:
 *  - GET  /api/resource?clientId=xxx   -> a rate-limited "protected" endpoint
 *  - GET  /admin/strategy               -> view active rate limiting strategy
 *  - POST /admin/strategy               -> switch active strategy at runtime
 *  - POST /admin/metrics/reset          -> start a fresh metrics CSV for a new experiment run
 *  - GET  /admin/metrics/live           -> live stats snapshot for the Phase 4 dashboard
 *
 * @EnableScheduling powers AdaptiveThresholdScheduler, which calls the
 * Phase 3 predictor once per second when ml-adaptive is the active strategy.
 * It runs harmlessly even when a different strategy is active (it just
 * updates a threshold nobody is reading).
 *
 * See DECISIONS.md at the project root for why things are built this way.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
