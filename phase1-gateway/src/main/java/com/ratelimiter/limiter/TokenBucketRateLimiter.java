package com.ratelimiter.limiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Classic token bucket, implemented per-client.
 *
 * Each client gets a bucket that holds up to `capacity` tokens.
 * Tokens refill continuously at `refillRatePerSec`. Every allowed request
 * consumes 1 token. If the bucket is empty, the request is rejected.
 *
 * Why lazy refill instead of a background thread ticking every bucket:
 * a scheduled thread waking up for every client, every tick, does not scale
 * and adds a whole class of timing bugs. Instead we compute "how many
 * tokens would have refilled since last time this client was seen" at the
 * moment of the request. This is the standard production approach (used by
 * e.g. Stripe's and Google's public write-ups on token bucket limiters).
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final int capacity;
    private final double refillRatePerSec;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacity, double refillRatePerSec) {
        this.capacity = capacity;
        this.refillRatePerSec = refillRatePerSec;
    }

    @Override
    public boolean tryAcquire(String clientId) {
        Bucket bucket = buckets.computeIfAbsent(clientId, id -> new Bucket(capacity));
        return bucket.tryConsume();
    }

    @Override
    public String name() {
        return "token-bucket";
    }

    /**
     * One bucket per client. Synchronized on the bucket instance (not a
     * global lock) so clients don't contend with each other -- only
     * concurrent requests from the SAME client serialize here, which is the
     * correct behaviour for a per-client limiter.
     */
    private class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(int initialTokens) {
            this.tokens = initialTokens;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            double tokensToAdd = elapsedSeconds * refillRatePerSec;
            if (tokensToAdd > 0) {
                tokens = Math.min(capacity, tokens + tokensToAdd);
                lastRefillNanos = now;
            }
        }
    }
}
