package com.nayem.laminar.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance rate limiter using the token bucket algorithm.
 * <p>
 * Provides per-key rate limiting with burst support. More efficient than sliding window
 * for high-throughput scenarios as it doesn't store individual request timestamps.
 * </p>
 * 
 * <h2>Algorithm</h2>
 * <p>
 * Tokens are added to the bucket at a fixed rate (refillRate per second). Each request
 * consumes one token. If no tokens are available, the request is rejected. The bucket
 * can hold up to 'capacity' tokens, allowing for burst traffic.
 * </p>
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Allow 100 requests/sec with burst capacity of 200
 * TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, Duration.ofSeconds(1), 200);
 * 
 * if (limiter.allowRequest("user-123")) {
 *     // Process request
 * } else {
 *     // Reject with 429 Too Many Requests
 * }
 * }</pre>
 * 
 * <h2>Thread Safety</h2>
 * <p>
 * This implementation is thread-safe and suitable for concurrent access from multiple threads.
 * Uses atomic operations for token management to minimize contention.
 * </p>
 * 
 * @see SlidingWindowRateLimiter
 */
public class TokenBucketRateLimiter {
    
    private final double refillRatePerMs;    // Tokens added per millisecond
    private final long refillRate;
    private final long timeUnitMs;
    private final long capacity;             // Maximum tokens in bucket
    private final Map<String, BucketState> buckets = new ConcurrentHashMap<>();
    
    /**
     * Creates a new token bucket rate limiter.
     *
     * @param refillRate number of tokens to add per second
     * @param timeUnit time unit for the refill rate
     * @param capacity maximum number of tokens the bucket can hold (burst capacity)
     */
    public TokenBucketRateLimiter(long refillRate, Duration timeUnit, long capacity) {
        if (refillRate <= 0) {
            throw new IllegalArgumentException("refillRate must be greater than 0");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be greater than 0");
        }
        long unitMs = timeUnit.toMillis();
        if (unitMs <= 0) {
            throw new IllegalArgumentException("timeUnit must be at least 1 millisecond");
        }
        this.refillRate = refillRate;
        this.timeUnitMs = unitMs;
        this.refillRatePerMs = (double) refillRate / unitMs;
        this.capacity = capacity;
    }
    
    /**
     * Creates a new token bucket rate limiter with equal refill rate and capacity.
     *
     * @param rate requests per second (also used as burst capacity)
     * @param timeUnit time unit for the rate
     */
    public TokenBucketRateLimiter(long rate, Duration timeUnit) {
        this(rate, timeUnit, rate);
    }
    
    /**
     * Checks if a request should be allowed for the given key.
     * <p>
     * If allowed, consumes one token from the bucket atomically.
     * </p>
     *
     * @param key the identifier (e.g., user ID, IP address, entity key)
     * @return true if the request is allowed, false if rate limited
     */
    public boolean allowRequest(String key) {
        long now = System.currentTimeMillis();
        BucketState state = buckets.computeIfAbsent(key, k -> new BucketState(capacity, now));
        
        synchronized (state) {
            refill(state, now);
            
            // Try to consume a token
            if (state.tokens >= 1) {
                state.tokens--;
                state.totalRequests.incrementAndGet();
                return true;
            }
            
            state.rejectedRequests.incrementAndGet();
            return false;
        }
    }
    
    /**
     * Attempts to consume multiple tokens atomically.
     * <p>
     * Useful for batch operations or weighted requests.
     * </p>
     *
     * @param key the identifier
     * @param tokens number of tokens to consume
     * @return true if all tokens were consumed, false if insufficient tokens
     */
    public boolean allowRequest(String key, int tokens) {
        if (tokens <= 0) {
            return true;
        }
        
        long now = System.currentTimeMillis();
        BucketState state = buckets.computeIfAbsent(key, k -> new BucketState(capacity, now));
        
        synchronized (state) {
            refill(state, now);
            
            // Try to consume tokens
            if (state.tokens >= tokens) {
                state.tokens -= tokens;
                state.totalRequests.addAndGet(tokens);
                return true;
            }
            
            state.rejectedRequests.addAndGet(tokens);
            return false;
        }
    }
    
    /**
     * Gets the current token count for a key.
     *
     * @param key the identifier
     * @return current number of tokens available
     */
    public long getAvailableTokens(String key) {
        long now = System.currentTimeMillis();
        BucketState state = buckets.get(key);
        if (state == null) {
            return capacity;
        }
        
        synchronized (state) {
            long elapsed = now - state.lastRefillTime;
            long tokensToAdd = (long) (elapsed * refillRatePerMs);
            return Math.min(capacity, state.tokens + tokensToAdd);
        }
    }
    
    /**
     * Gets rate limiting statistics for a key.
     *
     * @param key the identifier
     * @return map containing statistics
     */
    public Map<String, Object> getStats(String key) {
        BucketState state = buckets.get(key);
        if (state == null) {
            return Map.of(
                "key", key,
                "availableTokens", capacity,
                "capacity", capacity,
                "refillRatePerSec", (double) refillRate * 1000 / timeUnitMs,
                "totalRequests", 0,
                "rejectedRequests", 0,
                "rejectionRate", 0.0
            );
        }
        
        synchronized (state) {
            long available = getAvailableTokens(key);
            long total = state.totalRequests.get();
            long rejected = state.rejectedRequests.get();
            double rejectionRate = total > 0 ? (double) rejected / total : 0.0;
            
            return Map.of(
                "key", key,
                "availableTokens", available,
                "capacity", capacity,
                "refillRatePerSec", (double) refillRate * 1000 / timeUnitMs,
                "totalRequests", total,
                "rejectedRequests", rejected,
                "rejectionRate", rejectionRate
            );
        }
    }
    
    /**
     * Clears all rate limiting state.
     * <p>
     * Use with caution - typically only during testing or maintenance.
     * </p>
     */
    public void reset() {
        buckets.clear();
    }
    
    /**
     * Removes rate limiting state for a specific key.
     *
     * @param key the identifier to remove
     */
    public void remove(String key) {
        buckets.remove(key);
    }

    private void refill(BucketState state, long now) {
        long elapsed = now - state.lastRefillTime;
        if (elapsed <= 0) {
            return;
        }
        long tokensToAdd = (long) (elapsed * refillRatePerMs);
        if (tokensToAdd > 0) {
            state.tokens = Math.min(capacity, state.tokens + tokensToAdd);
            state.lastRefillTime = now;
        }
    }
    
    /**
     * Internal state holder for a token bucket.
     */
    private static class BucketState {
        volatile long tokens;
        volatile long lastRefillTime;
        final AtomicLong totalRequests = new AtomicLong(0);
        final AtomicLong rejectedRequests = new AtomicLong(0);
        
        BucketState(long initialTokens, long timestamp) {
            this.tokens = initialTokens;
            this.lastRefillTime = timestamp;
        }
    }
}
