package com.ratelimiter.metrics;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks allowed/rejected counts per second, purely in memory, so the
 * Phase 4 dashboard can poll a live snapshot without reading the CSV file
 * on every request (reading and parsing a growing CSV file on every poll
 * would get slower as the experiment runs longer -- an easy trap to fall
 * into and worth avoiding deliberately).
 */
@Component
public class LiveStatsService {

    private static final int RETAIN_SECONDS = 30;

    private final ConcurrentMap<Long, int[]> perSecondCounts = new ConcurrentHashMap<>();
    // int[0] = allowed, int[1] = rejected

    public void recordOutcome(boolean allowed) {
        long second = System.currentTimeMillis() / 1000;
        int[] counts = perSecondCounts.computeIfAbsent(second, s -> new int[2]);
        synchronized (counts) {
            if (allowed) counts[0]++; else counts[1]++;
        }
        prune(second);
    }

    public record Snapshot(long second, int allowed, int rejected) {}

    public Snapshot lastCompleteSecond() {
        long currentSecond = System.currentTimeMillis() / 1000;
        long lastComplete = currentSecond - 1;
        int[] counts = perSecondCounts.get(lastComplete);
        if (counts == null) return new Snapshot(lastComplete, 0, 0);
        synchronized (counts) {
            return new Snapshot(lastComplete, counts[0], counts[1]);
        }
    }

    private void prune(long currentSecond) {
        long cutoff = currentSecond - RETAIN_SECONDS;
        perSecondCounts.keySet().removeIf(s -> s < cutoff);
    }
}
