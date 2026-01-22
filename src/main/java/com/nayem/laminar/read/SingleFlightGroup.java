package com.nayem.laminar.read;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    private final ConcurrentHashMap<String, FlightFuture<V>> flights = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

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
        final java.util.Map<String, String> mdcContext = org.slf4j.MDC.getCopyOfContextMap();
        final Object securityContext = SecurityContextAccessor.get();

        FlightFuture<V> future = flights.computeIfAbsent(key, k -> {
            FlightFuture<V> flight = new FlightFuture<>();

            Future<?> task = executor.submit(() -> {
                if (mdcContext != null) {
                    org.slf4j.MDC.setContextMap(mdcContext);
                }
                SecurityContextAccessor.set(securityContext);

                try {
                    V result = supplier.get();
                    flight.complete(result);
                } catch (Throwable e) {
                    flight.completeExceptionally(e);
                } finally {
                    org.slf4j.MDC.clear();
                    SecurityContextAccessor.clear();
                    flights.remove(key, flight);
                }
            });
            
            flight.setTask(task);
            return flight;
        });

        if (timeout != null) {
            return future.orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        return future;
    }

    /**
     * Helper to safely access Spring Security Context without hard runtime
     * dependency.
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
     * A CompletableFuture that holds a reference to the underlying execution task
     * to allow propagation of cancellation (interruption).
     */
    private static class FlightFuture<V> extends CompletableFuture<V> {
        private volatile Future<?> task;

        void setTask(Future<?> task) {
            this.task = task;
            if (isCancelled()) {
                task.cancel(true);
            }
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
