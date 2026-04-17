package com.nayem.laminar.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter implementation using the sliding window log algorithm.
 * <p>
 * Provides per-key rate limiting to prevent abuse and ensure fair resource usage.
 * Suitable for protecting entity workers from overload by individual clients or entities.
 * </p>
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * RateLimiter limiter = new RateLimiter(100, Duration.ofSeconds(1));
 * 
 * if (limiter.allowRequest("user-123")) {
 *     // Process request
 * } else {
 *     // Reject with 429 Too Many Requests
 * }
 * }</pre>
 * 
 * <h2>Algorithm</h2>
 * <p>
 * Uses a sliding window approach that tracks timestamps of requests within the window.
 * More accurate than fixed windows but requires more memory. For high-throughput scenarios,
 * consider using {@link TokenBucketRateLimiter} instead.
 * </p>
 * 
 * @see TokenBucketRateLimiter
 */
public class SlidingWindowRateLimiter {
    
    private final int maxRequests;
    private final Duration windowSize;
    private final Map<String, WindowState> windows = new ConcurrentHashMap<>();
    
    /**
     * Creates a new rate limiter.
     *
     * @param maxRequests maximum number of requests allowed per window
     * @param windowSize the size of the sliding window
     */
    public SlidingWindowRateLimiter(int maxRequests, Duration windowSize) {
        this.maxRequests = maxRequests;
        this.windowSize = windowSize;
    }
    
    /**
     * Checks if a request should be allowed for the given key.
     *
     * @param key the identifier (e.g., user ID, IP address, entity key)
     * @return true if the request is allowed, false if rate limited
     */
    public boolean allowRequest(String key) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowSize.toMillis();
        
        WindowState state = windows.computeIfAbsent(key, k -> new WindowState());
        
        synchronized (state) {
            // Remove timestamps outside the window
            while (!state.timestamps.isEmpty() && state.timestamps.peekFirst() < windowStart) {
                state.timestamps.pollFirst();
            }
            
            // Check if under limit
            if (state.timestamps.size() < maxRequests) {
                state.timestamps.addLast(now);
                state.totalRequests.incrementAndGet();
                return true;
            }
            
            state.rejectedRequests.incrementAndGet();
            return false;
        }
    }
    
    /**
     * Gets the current request count for a key within the window.
     *
     * @param key the identifier
     * @return current request count
     */
    public int getCurrentCount(String key) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowSize.toMillis();
        
        WindowState state = windows.get(key);
        if (state == null) {
            return 0;
        }
        
        synchronized (state) {
            // Count timestamps within window
            return (int) state.timestamps.stream()
                .filter(ts -> ts >= windowStart)
                .count();
        }
    }
    
    /**
     * Gets rate limiting statistics for a key.
     *
     * @param key the identifier
     * @return map containing statistics
     */
    public Map<String, Object> getStats(String key) {
        WindowState state = windows.get(key);
        if (state == null) {
            return Map.of(
                "key", key,
                "currentCount", 0,
                "totalRequests", 0,
                "rejectedRequests", 0,
                "rejectionRate", 0.0
            );
        }
        
        synchronized (state) {
            int current = getCurrentCount(key);
            long total = state.totalRequests.get();
            long rejected = state.rejectedRequests.get();
            double rejectionRate = total > 0 ? (double) rejected / total : 0.0;
            
            return Map.of(
                "key", key,
                "currentCount", current,
                "maxRequests", maxRequests,
                "windowSizeMs", windowSize.toMillis(),
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
        windows.clear();
    }
    
    /**
     * Removes rate limiting state for a specific key.
     *
     * @param key the identifier to remove
     */
    public void remove(String key) {
        windows.remove(key);
    }
    
    /**
     * Internal state holder for a rate limiting window.
     */
    private static class WindowState {
        final java.util.Deque<Long> timestamps = new java.util.ArrayDeque<>();
        final AtomicInteger totalRequests = new AtomicInteger(0);
        final AtomicInteger rejectedRequests = new AtomicInteger(0);
    }
}
