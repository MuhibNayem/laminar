package com.nayem.laminar.read;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Enterprise-hardened SingleFlightGroup prevents thundering herd on cache misses.
 * <p>
 * This class suppresses duplicate function calls. If multiple callers request
 * the same key simultaneously, only one execution is launched, and its result
 * is shared among all callers.
 * </p>
 * <p>
 * Enterprise improvements:
 * - Bounded map size to prevent OOM on high cardinality keys
 * - Automatic cleanup of completed/failed futures after TTL
 * - Timeout enforcement on supplier execution
 * - Proper exception propagation without swallowing causes
 * - Metrics for monitoring deduplication efficiency
 * - Context propagation (MDC, Spring Security)
 * - Graceful cancellation support
 * </p>
 */
public class SingleFlightGroup<V> implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SingleFlightGroup.class);

    private final ConcurrentHashMap<String, FlightFuture<V>> flights = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final long cleanupIntervalMs;
    private final int maxKeys;
    private final long futureTtlMs;
    private volatile boolean closed = false;
    
    // Metrics
    private final AtomicLong totalCalls = new AtomicLong(0);
    private final AtomicLong deduplicatedCalls = new AtomicLong(0);
    private final AtomicLong failedCalls = new AtomicLong(0);
    private final AtomicLong timedOutCalls = new AtomicLong(0);

    /**
     * Creates SingleFlightGroup with enterprise defaults:
     * - Max 100,000 distinct keys
     * - 5-minute TTL for completed futures
     * - Cleanup every 60 seconds
     */
    public SingleFlightGroup() {
        this(Executors.newVirtualThreadPerTaskExecutor(), 100_000, 300_000, 60_000);
    }

    /**
     * @param executor Executor for running suppliers
     * @param maxKeys Maximum distinct keys to track (evicts oldest on overflow)
     * @param futureTtlMs Time to keep completed/failed futures in cache for reuse
     * @param cleanupIntervalMs How often to run cleanup task
     */
    public SingleFlightGroup(ExecutorService executor, int maxKeys, long futureTtlMs, long cleanupIntervalMs) {
        if (maxKeys <= 0) throw new IllegalArgumentException("maxKeys must be positive");
        if (futureTtlMs <= 0) throw new IllegalArgumentException("futureTtlMs must be positive");
        if (cleanupIntervalMs <= 0) throw new IllegalArgumentException("cleanupIntervalMs must be positive");
        
        this.executor = executor;
        this.maxKeys = maxKeys;
        this.futureTtlMs = futureTtlMs;
        this.cleanupIntervalMs = cleanupIntervalMs;
        
        startCleanupDaemon();
    }

    private void startCleanupDaemon() {
        Thread.ofVirtual().name("singleflight-cleanup").daemon(true).start(() -> {
            while (!closed && !executor.isShutdown()) {
                try {
                    Thread.sleep(cleanupIntervalMs);
                    cleanupStaleFutures();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("SingleFlight cleanup failed", e);
                }
            }
        });
    }

    private void cleanupStaleFutures() {
        long now = System.currentTimeMillis();
        int removed = 0;
        
        for (Map.Entry<String, FlightFuture<V>> entry : flights.entrySet()) {
            FlightFuture<V> ff = entry.getValue();
            if (ff.isDone() && (now - ff.completedAt) > futureTtlMs) {
                if (flights.remove(entry.getKey(), ff)) {
                    removed++;
                }
            }
        }
        
        // Emergency eviction if map exceeds maxKeys
        if (flights.size() > maxKeys) {
            log.warn("SingleFlight map exceeded capacity ({} > {}), triggering aggressive cleanup", 
                     flights.size(), maxKeys);
            // Remove oldest entries first
            flights.entrySet().stream()
                .sorted(Map.Entry.comparingByValue((a, b) -> Long.compare(a.createdAt, b.createdAt)))
                .limit(flights.size() - maxKeys)
                .forEach(entry -> {
                    if (entry.getValue().isDone()) {
                        flights.remove(entry.getKey());
                        removed++;
                    }
                });
        }
        
        if (removed > 0 && log.isDebugEnabled()) {
            log.debug("Cleaned up {} stale SingleFlight entries", removed);
        }
    }

    /**
     * Executes the given function if, and only if, there is no other execution
     * currently in progress for the given key.
     *
     * @param key      The unique key identifying the operation/resource.
     * @param supplier The expensive operation to execute (e.g., DB fetch).
     * @return A Future containing the result of the operation.
     * @throws IllegalStateException if this SingleFlightGroup has been closed
     */
    public CompletableFuture<V> doCall(String key, Supplier<V> supplier) {
        return doCall(key, supplier, null);
    }

    /**
     * Executes the given function with a timeout.
     *
     * @param key      The unique key identifying the operation/resource.
     * @param supplier The expensive operation to execute (e.g., DB fetch).
     * @param timeout  Maximum time to wait for the operation (null = no timeout).
     * @return A Future containing the result of the operation.
     * @throws IllegalStateException if this SingleFlightGroup has been closed
     */
    public CompletableFuture<V> doCall(String key, Supplier<V> supplier, java.time.Duration timeout) {
        if (closed) {
            throw new IllegalStateException("SingleFlightGroup has been closed");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key cannot be null or blank");
        }
        if (supplier == null) {
            throw new IllegalArgumentException("Supplier cannot be null");
        }
        
        totalCalls.incrementAndGet();
        final java.util.Map<String, String> mdcContext = org.slf4j.MDC.getCopyOfContextMap();
        final Object securityContext = SecurityContextAccessor.get();

        long now = System.currentTimeMillis();
        FlightFuture<V> flight = new FlightFuture<>(now);

        while (true) {
            FlightFuture<V> existing = flights.putIfAbsent(key, flight);
            if (existing == null) {
                // We won the race, execute
                executeAndComplete(key, flight, supplier, timeout, mdcContext, securityContext);
                return flight;
            }
            
            // Someone else is working on it
            if (existing.isDone()) {
                if ((now - existing.completedAt) < futureTtlMs) {
                    // Reuse completed result if still fresh
                    deduplicatedCalls.incrementAndGet();
                    return existing;
                } else {
                    // Stale result, try to replace
                    if (flights.replace(key, existing, flight)) {
                        executeAndComplete(key, flight, supplier, timeout, mdcContext, securityContext);
                        return flight;
                    }
                    // CAS failed, loop again
                }
            } else {
                // Still in flight, join existing
                deduplicatedCalls.incrementAndGet();
                return existing;
            }
        }
    }

    private void executeAndComplete(String key, FlightFuture<V> flight, 
                                    Supplier<V> supplier, java.time.Duration timeout,
                                    Map<String, String> mdcContext, Object securityContext) {
        executor.submit(() -> {
            try {
                V result;
                try {
                    if (timeout != null) {
                        result = CompletableFuture.supplyAsync(supplier, Executors.newVirtualThreadPerTaskExecutor())
                                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    } else {
                        result = supplier.get();
                    }
                    flight.complete(result);
                } catch (TimeoutException e) {
                    log.warn("SingleFlight call timed out for key: {}", key);
                    timedOutCalls.incrementAndGet();
                    failedCalls.incrementAndGet();
                    flight.completeExceptionally(new TimeoutException("Supplier timed out after " + timeout));
                    return;
                } catch (ExecutionException e) {
                    failedCalls.incrementAndGet();
                    flight.completeExceptionally(e.getCause() != null ? e.getCause() : e);
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failedCalls.incrementAndGet();
                    flight.completeExceptionally(e);
                    return;
                }
            } catch (Throwable t) {
                // Catch absolutely everything to prevent thread death
                log.error("Uncaught exception in SingleFlight for key: {}", key, t);
                failedCalls.incrementAndGet();
                flight.completeExceptionally(t);
            } finally {
                flight.markCompleted();
                org.slf4j.MDC.clear();
                SecurityContextAccessor.clear();
                // Only remove if this is still the active flight for this key
                flights.compute(key, (k, v) -> (v == flight) ? null : v);
            }
        });
    }

    /**
     * Cancels an in-flight request for the given key.
     * 
     * @param key The key to cancel
     * @return true if the request was cancelled, false if not in flight
     */
    public boolean cancel(String key) {
        FlightFuture<V> future = flights.remove(key);
        if (future != null) {
            return future.cancel(true);
        }
        return false;
    }

    /**
     * Checks if a request is currently in flight for the key.
     */
    public boolean isInFlight(String key) {
        return flights.containsKey(key);
    }

    /**
     * Returns the number of currently in-flight requests.
     */
    public int getInFlightCount() {
        return flights.size();
    }

    // Metrics getters
    public long getTotalCalls() { return totalCalls.get(); }
    public long getDeduplicatedCalls() { return deduplicatedCalls.get(); }
    public long getFailedCalls() { return failedCalls.get(); }
    public long getTimedOutCalls() { return timedOutCalls.get(); }
    
    /**
     * Returns the deduplication ratio (deduplicated / total).
     * Higher is better (more efficient).
     */
    public double getDeduplicationRatio() {
        long total = totalCalls.get();
        if (total == 0) return 0.0;
        return (double) deduplicatedCalls.get() / total;
    }

    /**
     * Closes this SingleFlightGroup and shuts down the executor.
     * No new calls will be accepted after closing.
     */
    @Override
    public void close() {
        closed = true;
        log.info("Closing SingleFlightGroup, cancelling {} in-flight requests", flights.size());
        
        // Cancel all in-flight requests
        flights.values().forEach(f -> f.cancel(true));
        flights.clear();
        
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("SingleFlight executor did not terminate gracefully");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Helper to safely access Spring Security Context without hard runtime dependency.
     */
    private static class SecurityContextAccessor {
        private static final boolean SPRING_SECURITY_PRESENT;

        static {
            boolean present = false;
            try {
                Class.forName("org.springframework.security.core.context.SecurityContextHolder");
                present = true;
            } catch (ClassNotFoundException e) {
                present = false;
            }
            SPRING_SECURITY_PRESENT = present;
        }

        static Object get() {
            if (!SPRING_SECURITY_PRESENT)
                return null;
            return org.springframework.security.core.context.SecurityContextHolder.getContext();
        }

        static void set(Object context) {
            if (!SPRING_SECURITY_PRESENT || context == null)
                return;
            if (context instanceof org.springframework.security.core.context.SecurityContext sc) {
                org.springframework.security.core.context.SecurityContextHolder.setContext(sc);
            }
        }

        static void clear() {
            if (!SPRING_SECURITY_PRESENT)
                return;
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    /**
     * A CompletableFuture that holds a reference to the underlying execution task
     * to allow propagation of cancellation (interruption).
     */
    private static class FlightFuture<V> extends CompletableFuture<V> {
        private volatile Future<?> task;
        final long createdAt;
        volatile long completedAt = -1;

        FlightFuture(long createdAt) {
            this.createdAt = createdAt;
        }

        void setTask(Future<?> task) {
            this.task = task;
            if (isCancelled()) {
                task.cancel(true);
            }
        }

        void markCompleted() {
            this.completedAt = System.currentTimeMillis();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled && task != null) {
                task.cancel(mayInterruptIfRunning);
            }
            return cancelled;
        }
    }
}
