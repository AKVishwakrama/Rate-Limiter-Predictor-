package com.ratelimiter.scheduler;

import com.ratelimiter.config.RateLimiterProperties;
import com.ratelimiter.limiter.MlAdaptiveRateLimiter;
import com.ratelimiter.predictor.MlPredictorClient;
import com.ratelimiter.predictor.TrafficRateTracker;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Runs once per second (see @Scheduled below). Pulls recent global traffic
 * history, asks the ML predictor what next second's load will look like,
 * and translates that into a per-client threshold for MlAdaptiveRateLimiter.
 *
 * The threshold formula (see computeThreshold): if predicted load is HIGH
 * relative to the target system capacity, tighten the per-client threshold
 * to protect the system. If predicted load is LOW, loosen it so clients
 * aren't throttled more than necessary. This is the actual "adaptive"
 * behaviour the whole project is evaluating -- document the exact formula
 * used in your paper's methodology section, including the constants below,
 * and justify or tune them against your own experiment data rather than
 * treating them as fixed truths.
 *
 * Every prediction is also logged to predictions.csv so you can evaluate
 * the MODEL's own accuracy (predicted vs. what traffic actually was) --
 * this is a separate evaluation from the end-to-end latency/drop-rate
 * comparison and belongs in its own results table in the paper.
 */
@Component
public class AdaptiveThresholdScheduler {

    private static final int HISTORY_SECONDS = 10;

    private final TrafficRateTracker tracker;
    private final MlPredictorClient predictorClient;
    private final MlAdaptiveRateLimiter limiter;
    private final RateLimiterProperties.MlAdaptive config;
    private final Path predictionsLog;

    private int lastThreshold;

    public AdaptiveThresholdScheduler(TrafficRateTracker tracker,
                                       MlPredictorClient predictorClient,
                                       MlAdaptiveRateLimiter limiter,
                                       RateLimiterProperties props) {
        this.tracker = tracker;
        this.predictorClient = predictorClient;
        this.limiter = limiter;
        this.config = props.getMlAdaptive();
        this.lastThreshold = limiter.getCurrentThreshold();
        this.predictionsLog = Paths.get("metrics/predictions.csv");
        initLog();
    }

    private void initLog() {
        try {
            if (predictionsLog.getParent() != null) {
                Files.createDirectories(predictionsLog.getParent());
            }
            if (!Files.exists(predictionsLog)) {
                try (PrintWriter pw = new PrintWriter(new FileWriter(predictionsLog.toFile()))) {
                    pw.println("timestamp_ms,actual_last_second,predicted_next_second,applied_threshold,model_type");
                }
            }
        } catch (IOException e) {
            System.err.println("Could not init predictions log: " + e.getMessage());
        }
    }

    @Scheduled(fixedRate = 1000)
    public void updateThreshold() {
        List<Integer> history = tracker.getRecentHistory(HISTORY_SECONDS);
        int actualLastSecond = history.isEmpty() ? 0 : history.get(history.size() - 1);

        var predictionOpt = predictorClient.predict(history);

        int newThreshold;
        double predictedValue;
        String modelType;

        if (predictionOpt.isPresent()) {
            var prediction = predictionOpt.get();
            predictedValue = prediction.predictedNextSecond();
            modelType = prediction.modelType();
            newThreshold = computeThreshold(predictedValue);
            lastThreshold = newThreshold;
        } else {
            // Predictor unreachable this tick: hold the last known threshold
            // rather than guessing. See MlPredictorClient's Javadoc.
            predictedValue = -1;
            modelType = "unavailable-held-last-value";
            newThreshold = lastThreshold;
        }

        limiter.updateThreshold(newThreshold);
        logPrediction(actualLastSecond, predictedValue, newThreshold, modelType);
    }

    /**
     * Converts a predicted global request rate into a per-client threshold.
     *
     * targetGlobalCapacity: the total request/sec the system is designed to
     * comfortably handle (tune this to your gateway's real simulated
     * backend capacity -- see application.yml).
     *
     * The scaling logic: if predicted load exceeds target capacity, shrink
     * the per-client allowance proportionally. If predicted load is well
     * under capacity, allow up to the configured max per-client threshold.
     * Clamped between min and max so the limiter never goes to zero
     * (which would reject everyone) or unbounded (which defeats the point
     * of having a limiter at all).
     */
    private int computeThreshold(double predictedLoad) {
        double targetCapacity = config.getTargetGlobalCapacityPerSec();
        double baseThreshold = config.getMaxThreshold();

        if (predictedLoad <= targetCapacity) {
            return config.getMaxThreshold();
        }

        double overloadRatio = targetCapacity / predictedLoad; // < 1 when overloaded
        int scaled = (int) Math.round(baseThreshold * overloadRatio);
        return Math.max(config.getMinThreshold(), Math.min(config.getMaxThreshold(), scaled));
    }

    private void logPrediction(int actual, double predicted, int appliedThreshold, String modelType) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(predictionsLog.toFile(), true))) {
            pw.println(System.currentTimeMillis() + "," + actual + "," + predicted + "," +
                    appliedThreshold + "," + modelType);
        } catch (IOException e) {
            System.err.println("Failed to write prediction log row: " + e.getMessage());
        }
    }
}
