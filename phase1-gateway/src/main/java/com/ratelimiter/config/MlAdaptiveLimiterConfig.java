package com.ratelimiter.config;

import com.ratelimiter.limiter.MlAdaptiveRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Defines MlAdaptiveRateLimiter as a singleton Spring bean so that
 * RateLimiterRegistry (which routes requests to it) and
 * AdaptiveThresholdScheduler (which updates its threshold every second)
 * both hold a reference to the exact same instance. Building it inline in
 * two different places would silently create two independent limiters
 * with two independent thresholds -- a subtle bug worth preventing by
 * construction rather than by convention.
 */
@Configuration
public class MlAdaptiveLimiterConfig {

    @Bean
    public MlAdaptiveRateLimiter mlAdaptiveRateLimiter(RateLimiterProperties props) {
        var cfg = props.getMlAdaptive();
        return new MlAdaptiveRateLimiter(cfg.getWindowSizeMs(), cfg.getMaxThreshold());
    }
}
