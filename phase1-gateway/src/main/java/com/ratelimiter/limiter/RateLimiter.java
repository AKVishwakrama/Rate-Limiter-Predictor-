package com.ratelimiter.limiter;

/**
 * Strategy interface for a per-client rate limiting algorithm.
 * Every implementation must be thread-safe: multiple client requests can
 * arrive concurrently and this method will be called from multiple
 * request-handling threads at once.
 */
public interface RateLimiter {

    /**
     * @param clientId identifies the caller (in phase 1 this is just a query
     *                 param; in a real system it would come from an API key
     *                 or auth token).
     * @return true if the request is allowed under this client's current
     *         quota, false if it should be rejected (HTTP 429).
     */
    boolean tryAcquire(String clientId);

    /** Human-readable name used in metrics rows and admin responses. */
    String name();
}
