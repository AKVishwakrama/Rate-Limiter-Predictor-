package com.ratelimiter.model;

/**
 * One row of the metrics CSV. Kept as a plain immutable record --
 * this is exactly the shape phase 2's analysis script expects.
 */
public record RequestRecord(
        long timestampMs,
        String clientId,
        String strategy,
        boolean allowed,
        long latencyMs
) {
    public static String csvHeader() {
        return "timestamp_ms,client_id,strategy,allowed,latency_ms";
    }

    public String toCsvRow() {
        return timestampMs + "," + clientId + "," + strategy + "," + allowed + "," + latencyMs;
    }
}
