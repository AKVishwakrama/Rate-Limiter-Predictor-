package com.ratelimiter.controller;

import com.ratelimiter.limiter.RateLimiterRegistry;
import com.ratelimiter.metrics.LiveStatsService;
import com.ratelimiter.metrics.MetricsRecorder;
import com.ratelimiter.model.RequestRecord;
import com.ratelimiter.predictor.TrafficRateTracker;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The "protected" endpoint that traffic in phase 2 hits.
 *
 * Flow per request:
 *   1. Ask the active RateLimiter whether this clientId may proceed.
 *   2. If yes: simulate backend work (random 50-150ms delay), return 200.
 *   3. If no: return 429 immediately.
 *   4. Either way, record the outcome + latency to the metrics CSV.
 *
 * Why simulated in-process backend work instead of proxying to a real
 * downstream service: it isolates the rate limiter's behaviour from
 * network variability between two services, which would otherwise be a
 * confounding variable in the latency numbers. See DECISIONS.md.
 */
@RestController
public class GatewayController {

    private final RateLimiterRegistry registry;
    private final MetricsRecorder metrics;
    private final TrafficRateTracker trafficRateTracker;
    private final LiveStatsService liveStats;

    public GatewayController(RateLimiterRegistry registry, MetricsRecorder metrics,
                              TrafficRateTracker trafficRateTracker, LiveStatsService liveStats) {
        this.registry = registry;
        this.metrics = metrics;
        this.trafficRateTracker = trafficRateTracker;
        this.liveStats = liveStats;
    }

    @GetMapping("/api/resource")
    public ResponseEntity<Map<String, Object>> getResource(
            @RequestParam(defaultValue = "anonymous") String clientId) {

        // Recorded unconditionally (regardless of active strategy) so the
        // ML predictor always has real traffic history available the
        // moment someone switches to ml-adaptive mid-experiment.
        trafficRateTracker.recordRequest();

        long start = System.nanoTime();
        boolean allowed = registry.tryAcquire(clientId);
        String strategy = registry.activeStrategyName();
        liveStats.recordOutcome(allowed);

        ResponseEntity<Map<String, Object>> response;
        if (allowed) {
            simulateBackendWork();
            response = ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "clientId", clientId,
                    "strategy", strategy
            ));
        } else {
            response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "status", "rejected",
                    "clientId", clientId,
                    "strategy", strategy
            ));
        }

        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        metrics.record(new RequestRecord(
                System.currentTimeMillis(), clientId, strategy, allowed, latencyMs));

        return response;
    }

    /** Stand-in for real downstream processing time. */
    private void simulateBackendWork() {
        try {
            int delayMs = ThreadLocalRandom.current().nextInt(50, 151);
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
