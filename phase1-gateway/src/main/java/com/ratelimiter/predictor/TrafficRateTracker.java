package com.ratelimiter.predictor;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks how many requests have arrived globally (across all clients) in
 * each recent second. This is the raw input the ML predictor consumes --
 * it forecasts "how many requests next second," not per-client behaviour.
 *
 * Bucketed by second using System.currentTimeMillis()/1000 as the key.
 * Old buckets are pruned so this doesn't grow unbounded over a long-running
 * gateway process.
 */
@Component
public class TrafficRateTracker {

    private static final int MAX_HISTORY_SECONDS = 120;

    private final ConcurrentMap<Long, java.util.concurrent.atomic.AtomicInteger> countsBySecond =
            new ConcurrentHashMap<>();

    public void recordRequest() {
        long second = System.currentTimeMillis() / 1000;
        countsBySecond.computeIfAbsent(second, s -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();
        pruneOld(second);
    }

    /**
     * @param lastNSeconds how many seconds of history to return
     * @return counts oldest-first, zero-filled for seconds with no traffic
     */
    public List<Integer> getRecentHistory(int lastNSeconds) {
        long currentSecond = System.currentTimeMillis() / 1000;
        List<Integer> history = new ArrayList<>(lastNSeconds);
        // We use currentSecond - 1 as the most recent COMPLETE second --
        // the current second is still in progress and would undercount.
        for (long s = currentSecond - lastNSeconds; s < currentSecond; s++) {
            var counter = countsBySecond.get(s);
            history.add(counter == null ? 0 : counter.get());
        }
        return history;
    }

    private void pruneOld(long currentSecond) {
        long cutoff = currentSecond - MAX_HISTORY_SECONDS;
        countsBySecond.keySet().removeIf(second -> second < cutoff);
    }
}
