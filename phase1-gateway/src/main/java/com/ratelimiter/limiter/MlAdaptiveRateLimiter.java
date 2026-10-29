package com.ratelimiter.limiter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sliding-window-log limiter (same core algorithm as SlidingWindowRateLimiter)
 * but with a MUTABLE per-client threshold, updated once per second by
 * AdaptiveThresholdScheduler based on the ML predictor's forecast.
 *
 * Why reuse the sliding window algorithm instead of inventing a new one:
 * the research question is "does an adaptive THRESHOLD beat a static one,"
 * not "is sliding-window-log a good algorithm." Changing both the
 * algorithm AND the threshold-setting strategy at once would make it
 * impossible to attribute a result to either change specifically. Keeping
 * the underlying counting mechanism identical to the static sliding-window
 * baseline isolates the one variable actually being tested.
 */
public class MlAdaptiveRateLimiter implements RateLimiter {

    private final long windowSizeMs;
    private final AtomicInteger maxRequests;
    private final ConcurrentMap<String, Deque<Long>> clientLogs = new ConcurrentHashMap<>();

    public MlAdaptiveRateLimiter(long windowSizeMs, int initialMaxRequests) {
        this.windowSizeMs = windowSizeMs;
        this.maxRequests = new AtomicInteger(initialMaxRequests);
    }

    @Override
    public boolean tryAcquire(String clientId) {
        Deque<Long> log = clientLogs.computeIfAbsent(clientId, id -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        int currentMax = maxRequests.get();

        synchronized (log) {
            evictOld(log, now);
            if (log.size() < currentMax) {
                log.addLast(now);
                return true;
            }
            return false;
        }
    }

    private void evictOld(Deque<Long> log, long now) {
        long cutoff = now - windowSizeMs;
        while (!log.isEmpty() && log.peekFirst() < cutoff) {
            log.pollFirst();
        }
    }

    /** Called by AdaptiveThresholdScheduler once per second. */
    public void updateThreshold(int newMax) {
        maxRequests.set(newMax);
    }

    public int getCurrentThreshold() {
        return maxRequests.get();
    }

    @Override
    public String name() {
        return "ml-adaptive";
    }
}
