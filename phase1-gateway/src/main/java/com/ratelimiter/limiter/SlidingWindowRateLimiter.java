package com.ratelimiter.limiter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Sliding window log limiter, implemented per-client.
 *
 * Each client keeps a log (deque) of the timestamps of their recent
 * requests. On each new request we drop timestamps older than
 * `windowSizeMs` from the front of the deque, then check whether the
 * remaining count is under `maxRequests`.
 *
 * Trade-off vs token bucket (call this out in the paper/interview):
 * this is more memory-heavy (stores a timestamp per request in the window,
 * not just a counter) but gives an exact, not approximate, rolling window --
 * no boundary burst problem like fixed-window counters have. Token bucket
 * allows short bursts up to `capacity`; sliding window enforces a hard cap
 * over any rolling interval. That difference is the whole point of
 * comparing them under bursty traffic in phase 2.
 */
public class SlidingWindowRateLimiter implements RateLimiter {

    private final long windowSizeMs;
    private final int maxRequests;
    private final ConcurrentMap<String, Deque<Long>> clientLogs = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(long windowSizeMs, int maxRequests) {
        this.windowSizeMs = windowSizeMs;
        this.maxRequests = maxRequests;
    }

    @Override
    public boolean tryAcquire(String clientId) {
        Deque<Long> log = clientLogs.computeIfAbsent(clientId, id -> new ArrayDeque<>());
        long now = System.currentTimeMillis();

        synchronized (log) {
            evictOld(log, now);
            if (log.size() < maxRequests) {
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

    @Override
    public String name() {
        return "sliding-window";
    }
}
