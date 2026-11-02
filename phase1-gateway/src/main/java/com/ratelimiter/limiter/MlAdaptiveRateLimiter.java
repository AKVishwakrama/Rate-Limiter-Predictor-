//package com.ratelimiter.limiter;
//
//import java.util.ArrayDeque;
//import java.util.Deque;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.concurrent.ConcurrentMap;
//import java.util.concurrent.atomic.AtomicInteger;
//
///**
// * Sliding-window-log limiter (same core algorithm as SlidingWindowRateLimiter)
// * but with a MUTABLE per-client threshold, updated once per second by
// * AdaptiveThresholdScheduler based on the ML predictor's forecast.
// *
// * Why reuse the sliding window algorithm instead of inventing a new one:
// * the research question is "does an adaptive THRESHOLD beat a static one,"
// * not "is sliding-window-log a good algorithm." Changing both the
// * algorithm AND the threshold-setting strategy at once would make it
// * impossible to attribute a result to either change specifically. Keeping
// * the underlying counting mechanism identical to the static sliding-window
// * baseline isolates the one variable actually being tested.
// */
//public class MlAdaptiveRateLimiter implements RateLimiter {
//
//    private final long windowSizeMs;
//    private final AtomicInteger maxRequests;
//    private final ConcurrentMap<String, Deque<Long>> clientLogs = new ConcurrentHashMap<>();
//
//    public MlAdaptiveRateLimiter(long windowSizeMs, int initialMaxRequests) {
//        this.windowSizeMs = windowSizeMs;
//        this.maxRequests = new AtomicInteger(initialMaxRequests);
//    }
//
//    @Override
//    public boolean tryAcquire(String clientId) {
//        Deque<Long> log = clientLogs.computeIfAbsent(clientId, id -> new ArrayDeque<>());
//        long now = System.currentTimeMillis();
//        int currentMax = maxRequests.get();
//
//        synchronized (log) {
//            evictOld(log, now);
//            if (log.size() < currentMax) {
//                log.addLast(now);
//                return true;
//            }
//            return false;
//        }
//    }
//
//    private void evictOld(Deque<Long> log, long now) {
//        long cutoff = now - windowSizeMs;
//        while (!log.isEmpty() && log.peekFirst() < cutoff) {
//            log.pollFirst();
//        }
//    }
//
//    /** Called by AdaptiveThresholdScheduler once per second. */
//    public void updateThreshold(int newMax) {
//        maxRequests.set(newMax);
//    }
//
//    public int getCurrentThreshold() {
//        return maxRequests.get();
//    }
//
//    @Override
//    public String name() {
//        return "ml-adaptive";
//    }
//}



package com.ratelimiter.limiter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MlAdaptiveRateLimiter implements RateLimiter {

    private final long windowSizeMs;

    // Threshold can be updated dynamically by the scheduler.
    private final AtomicInteger maxRequests;

    // Separate request timestamp history for each client.
    private final ConcurrentMap<String, Deque<Long>> clientLogs =
            new ConcurrentHashMap<>();

    public MlAdaptiveRateLimiter(long windowSizeMs, int initialMaxRequests) {

        if (windowSizeMs <= 0) {
            throw new IllegalArgumentException(
                    "Window size must be greater than zero"
            );
        }

        if (initialMaxRequests <= 0) {
            throw new IllegalArgumentException(
                    "Initial threshold must be greater than zero"
            );
        }

        this.windowSizeMs = windowSizeMs;
        this.maxRequests = new AtomicInteger(initialMaxRequests);
    }

    @Override
    public boolean tryAcquire(String clientId) {

        // Get or create the request history for this client.
        Deque<Long> log = clientLogs.computeIfAbsent(
                clientId,
                id -> new ArrayDeque<>()
        );

        long now = System.currentTimeMillis();

        synchronized (log) {

            // Remove requests outside the current time window.
            evictOld(log, now);

            // Read the latest threshold after acquiring the lock.
            int currentMax = maxRequests.get();

            // Allow request if count is below the threshold.
            if (log.size() < currentMax) {

                log.addLast(now);

                return true;
            }

            // Reject request if threshold has been reached.
            return false;
        }
    }

    private void evictOld(Deque<Long> log, long now) {

        long cutoff = now - windowSizeMs;

        // Remove timestamps at or before the window boundary.
        while (!log.isEmpty() && log.peekFirst() <= cutoff) {
            log.pollFirst();
        }
    }

    /**
     * Called by AdaptiveThresholdScheduler to update
     * the per-client request threshold.
     */
    public void updateThreshold(int newMax) {

        if (newMax <= 0) {
            throw new IllegalArgumentException(
                    "Threshold must be greater than zero"
            );
        }

        maxRequests.set(newMax);
    }

    /**
     * Returns the current threshold.
     */
    public int getCurrentThreshold() {
        return maxRequests.get();
    }

    @Override
    public String name() {
        return "ml-adaptive";
    }
}
