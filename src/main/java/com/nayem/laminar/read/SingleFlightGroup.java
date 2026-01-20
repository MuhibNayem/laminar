package com.nayem.laminar.read;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Implements the "SingleFlight" pattern (popularized by Go's
 * sync/singleflight).
 * <p>
 * This class suppresses duplicate function calls. If multiple callers request
 * the same key simultaneously, only one execution is launched, and its result
 * is shared among all callers.
 * </p>
 * <p>
 * Useful for preventing Thundering Herd problems on cache misses.
 * </p>
 */
public class SingleFlightGroup<V> {

    private final ConcurrentHashMap<String, CompletableFuture<V>> flights = new ConcurrentHashMap<>();

    /**
     * Executes the given function if, and only if, there is no other execution
     * currently in progress for the given key.
     *
     * @param key      The unique key identifying the operation/resource.
     * @param supplier The expensive operation to execute (e.g., DB fetch).
     * @return A Future containing the result of the operation.
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
     */
    public CompletableFuture<V> doCall(String key, Supplier<V> supplier, java.time.Duration timeout) {
        CompletableFuture<V> future = flights.computeIfAbsent(key, k -> {
            CompletableFuture<V> newFuture = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    V result = supplier.get();
                    newFuture.complete(result);
                } catch (Exception e) {
                    newFuture.completeExceptionally(e);
                } finally {
                    flights.remove(key, newFuture);
                }
            });

            return newFuture;
        });

        // Apply timeout if specified
        if (timeout != null) {
            return future.orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        return future;
    }

    /**
     * Cancels an in-flight request for the given key.
     * 
     * @param key The key to cancel
     * @return true if the request was cancelled, false if not in flight
     */
    public boolean cancel(String key) {
        CompletableFuture<V> future = flights.remove(key);
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
}
