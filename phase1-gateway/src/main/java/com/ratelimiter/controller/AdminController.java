//package com.ratelimiter.controller;
//
//import com.ratelimiter.limiter.MlAdaptiveRateLimiter;
//import com.ratelimiter.limiter.RateLimiterRegistry;
//import com.ratelimiter.metrics.LiveStatsService;
//import com.ratelimiter.metrics.MetricsRecorder;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.HashMap;
//import java.util.Map;
//
///**
// * Operator endpoints used between experiment runs -- not part of the
// * benchmarked request path itself.
// *
// * Typical phase 2 workflow:
// *   1. POST /admin/strategy {"strategy":"token-bucket"}
// *   2. POST /admin/metrics/reset
// *   3. run simulator.py against a traffic pattern
// *   4. POST /admin/strategy {"strategy":"sliding-window"}
// *   5. POST /admin/metrics/reset
// *   6. run simulator.py again with the SAME pattern
// *   7. compare the two resulting CSVs with analyze_results.py
// */
//@RestController
//@RequestMapping("/admin")
//public class AdminController {
//
//    private final RateLimiterRegistry registry;
//    private final MetricsRecorder metrics;
//    private final LiveStatsService liveStats;
//    private final MlAdaptiveRateLimiter mlAdaptiveRateLimiter;
//
//    public AdminController(RateLimiterRegistry registry, MetricsRecorder metrics,
//                            LiveStatsService liveStats, MlAdaptiveRateLimiter mlAdaptiveRateLimiter) {
//        this.registry = registry;
//        this.metrics = metrics;
//        this.liveStats = liveStats;
//        this.mlAdaptiveRateLimiter = mlAdaptiveRateLimiter;
//    }
//
//    /**
//     * Polled by the Phase 4 dashboard roughly once per second. Returns the
//     * last COMPLETE second's allowed/rejected counts (not the in-progress
//     * current second, which would look artificially low mid-count).
//     */
//    @GetMapping("/metrics/live")
//    public Map<String, Object> liveMetrics() {
//        var snapshot = liveStats.lastCompleteSecond();
//        Map<String, Object> body = new HashMap<>();
//        body.put("second", snapshot.second());
//        body.put("allowed", snapshot.allowed());
//        body.put("rejected", snapshot.rejected());
//        body.put("activeStrategy", registry.activeStrategyName());
//        if ("ml-adaptive".equals(registry.activeStrategyName())) {
//            body.put("currentThreshold", mlAdaptiveRateLimiter.getCurrentThreshold());
//        }
//        return body;
//    }
//
//    @GetMapping("/strategy")
//    public Map<String, Object> getStrategy() {
//        return Map.of(
//                "active", registry.activeStrategyName(),
//                "available", registry.allStrategies().keySet()
//        );
//    }
//
//    public record StrategySwitchRequest(String strategy) {}
//
//    @PostMapping("/strategy")
//    public ResponseEntity<Map<String, Object>> switchStrategy(@RequestBody StrategySwitchRequest req) {
//        boolean switched = registry.switchTo(req.strategy());
//        if (!switched) {
//            return ResponseEntity.badRequest().body(Map.of(
//                    "error", "unknown strategy: " + req.strategy(),
//                    "available", registry.allStrategies().keySet()
//            ));
//        }
//        return ResponseEntity.ok(Map.of("active", registry.activeStrategyName()));
//    }
//
//    @PostMapping("/metrics/reset")
//    public ResponseEntity<Map<String, Object>> resetMetrics() {
//        metrics.reset();
//        return ResponseEntity.ok(Map.of(
//                "status", "reset",
//                "file", metrics.getOutputFilePath()
//        ));
//    }
//}





package com.ratelimiter.controller;

import com.ratelimiter.limiter.MlAdaptiveRateLimiter;
import com.ratelimiter.limiter.RateLimiterRegistry;
import com.ratelimiter.metrics.LiveStatsService;
import com.ratelimiter.metrics.MetricsRecorder;
import com.ratelimiter.scheduler.AdaptiveThresholdScheduler;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final RateLimiterRegistry registry;
    private final MetricsRecorder metrics;
    private final LiveStatsService liveStats;
    private final MlAdaptiveRateLimiter mlAdaptiveRateLimiter;
    private final AdaptiveThresholdScheduler scheduler;

    public AdminController(
            RateLimiterRegistry registry,
            MetricsRecorder metrics,
            LiveStatsService liveStats,
            MlAdaptiveRateLimiter mlAdaptiveRateLimiter,
            AdaptiveThresholdScheduler scheduler) {

        this.registry = registry;
        this.metrics = metrics;
        this.liveStats = liveStats;
        this.mlAdaptiveRateLimiter = mlAdaptiveRateLimiter;
        this.scheduler = scheduler;
    }

    /**
     * Polled by the Phase 4 dashboard.
     * Returns live traffic statistics and ML prediction details.
     */
    @GetMapping("/metrics/live")
    public Map<String, Object> liveMetrics() {

        var snapshot = liveStats.lastCompleteSecond();

        Map<String, Object> body = new HashMap<>();

        // Existing traffic metrics
        body.put("second", snapshot.second());
        body.put("allowed", snapshot.allowed());
        body.put("rejected", snapshot.rejected());
        body.put("activeStrategy", registry.activeStrategyName());

        // Existing threshold information
        if ("ml-adaptive".equals(registry.activeStrategyName())) {

            body.put(
                    "currentThreshold",
                    mlAdaptiveRateLimiter.getCurrentThreshold()
            );

            // ML prediction information
            body.put(
                    "predictionAvailable",
                    scheduler.isPredictionAvailable()
            );

            body.put(
                    "predictedNextSecond",
                    scheduler.getLatestPredictedTraffic()
            );

            body.put(
                    "actualLastSecond",
                    scheduler.getLatestActualTraffic()
            );

            body.put(
                    "modelType",
                    scheduler.getLatestModelType()
            );

            body.put(
                    "predictionStatus",
                    scheduler.getPredictionStatus()
            );

            body.put(
                    "appliedThreshold",
                    scheduler.getLatestAppliedThreshold()
            );

            body.put(
                    "lastPredictionTimestamp",
                    scheduler.getLastPredictionTimestamp()
            );
        }

        return body;
    }

    /**
     * Returns the currently active strategy and available strategies.
     */
    @GetMapping("/strategy")
    public Map<String, Object> getStrategy() {

        return Map.of(
                "active", registry.activeStrategyName(),
                "available", registry.allStrategies().keySet()
        );
    }

    public record StrategySwitchRequest(String strategy) {}

    /**
     * Switches between Token Bucket, Sliding Window and ML Adaptive.
     */
    @PostMapping("/strategy")
    public ResponseEntity<Map<String, Object>> switchStrategy(
            @RequestBody StrategySwitchRequest req) {

        boolean switched = registry.switchTo(req.strategy());

        if (!switched) {

            return ResponseEntity.badRequest().body(Map.of(
                    "error", "unknown strategy: " + req.strategy(),
                    "available", registry.allStrategies().keySet()
            ));
        }

        return ResponseEntity.ok(Map.of(
                "active", registry.activeStrategyName()
        ));
    }

    /**
     * Resets experiment metrics.
     */
    @PostMapping("/metrics/reset")
    public ResponseEntity<Map<String, Object>> resetMetrics() {

        metrics.reset();

        return ResponseEntity.ok(Map.of(
                "status", "reset",
                "file", metrics.getOutputFilePath()
        ));
    }
}
