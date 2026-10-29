package com.ratelimiter.limiter;

import com.ratelimiter.config.RateLimiterProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds ALL strategy instances (not just the active one) and exposes a
 * single switch point.
 *
 * Why this design instead of separate deployed instances per strategy:
 * running static vs adaptive limiting as separate services would mean
 * different JVMs, different warm-up states, possibly different machines --
 * every one of those is a confounding variable in a benchmark comparison.
 * By keeping one process and flipping an AtomicReference, the only thing
 * that changes between experiment runs is the algorithm itself. See
 * DECISIONS.md, "Why a unified pluggable gateway instead of three
 * separate services".
 */
@Component
public class RateLimiterRegistry {

    private final Map<String, RateLimiter> strategies;
    private final AtomicReference<String> activeStrategy;
    private final MlAdaptiveRateLimiter mlAdaptiveLimiter;

    /**
     * The MlAdaptiveRateLimiter is injected as a Spring bean (not built
     * inline like the other two) because AdaptiveThresholdScheduler also
     * needs a reference to the SAME instance to update its threshold every
     * second. See config.MlAdaptiveLimiterConfig for the bean definition.
     */
    public RateLimiterRegistry(RateLimiterProperties props, MlAdaptiveRateLimiter mlAdaptiveLimiter) {
        TokenBucketRateLimiter tokenBucket = new TokenBucketRateLimiter(
                props.getTokenBucket().getCapacity(),
                props.getTokenBucket().getRefillRatePerSec());

        SlidingWindowRateLimiter slidingWindow = new SlidingWindowRateLimiter(
                props.getSlidingWindow().getWindowSizeMs(),
                props.getSlidingWindow().getMaxRequests());

        this.mlAdaptiveLimiter = mlAdaptiveLimiter;

        this.strategies = Map.of(
                tokenBucket.name(), tokenBucket,
                slidingWindow.name(), slidingWindow,
                mlAdaptiveLimiter.name(), mlAdaptiveLimiter
        );

        if (!strategies.containsKey(props.getStrategy())) {
            throw new IllegalStateException(
                    "Unknown ratelimiter.strategy '" + props.getStrategy() +
                    "'. Valid options: " + strategies.keySet());
        }
        this.activeStrategy = new AtomicReference<>(props.getStrategy());
    }

    public boolean tryAcquire(String clientId) {
        return active().tryAcquire(clientId);
    }

    public RateLimiter active() {
        return strategies.get(activeStrategy.get());
    }

    public String activeStrategyName() {
        return activeStrategy.get();
    }

    /** @return true if switched, false if the requested strategy name is unknown. */
    public boolean switchTo(String strategyName) {
        if (!strategies.containsKey(strategyName)) {
            return false;
        }
        activeStrategy.set(strategyName);
        return true;
    }

    public Map<String, RateLimiter> allStrategies() {
        return strategies;
    }
}
