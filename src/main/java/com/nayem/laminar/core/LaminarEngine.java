package com.nayem.laminar.core;

import com.nayem.laminar.dlq.DeadLetterQueue;
import com.nayem.laminar.worker.EntityWorker;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class LaminarEngine<T> implements LaminarDispatcher<T>, AutoCloseable {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LaminarEngine.class);

    private final com.github.benmanes.caffeine.cache.Cache<String, EntityWorker<T>> workers;
    private final Consumer<CoalescingBatch<T>> dbExecutor;
    private final io.micrometer.core.instrument.MeterRegistry registry;
    private final int maxWaiters;
    private final int maxBatchSize;
    private final int maxCoalesceIterations;
    private final Duration shutdownTimeout;
    private final Duration shutdownPollingInterval;
    private final String threadNamePrefix;
    private final java.util.concurrent.ExecutorService sharedExecutor;
    private final java.util.concurrent.Semaphore globalSemaphore;
    private final DeadLetterQueue<T> dlq; // Optional DLQ

    public LaminarEngine(Consumer<CoalescingBatch<T>> dbExecutor,
            io.micrometer.core.instrument.MeterRegistry registry,
            int maxWaiters,
            int maxBatchSize,
            int maxCoalesceIterations,
            Duration workerEvictionTime,
            long maxCachedWorkers,
            Duration shutdownTimeout,
            Duration shutdownPollingInterval,
            String threadNamePrefix,
            java.util.concurrent.ExecutorService sharedExecutor,
            DeadLetterQueue<T> dlq) {
        this.dbExecutor = dbExecutor;
        this.registry = registry;
        this.maxWaiters = maxWaiters;
        this.maxBatchSize = maxBatchSize;
        this.maxCoalesceIterations = maxCoalesceIterations;
        this.shutdownTimeout = shutdownTimeout;
        this.shutdownPollingInterval = shutdownPollingInterval;
        this.threadNamePrefix = threadNamePrefix;
        this.sharedExecutor = sharedExecutor;
        this.dlq = dlq;
        this.globalSemaphore = new java.util.concurrent.Semaphore(maxWaiters * 10); // Global limit

        com.github.benmanes.caffeine.cache.Caffeine<Object, Object> builder = com.github.benmanes.caffeine.cache.Caffeine
                .newBuilder()
                .expireAfterAccess(workerEvictionTime);

        if (maxCachedWorkers > 0) {
            builder.maximumSize(maxCachedWorkers);
        }

        this.workers = builder.build();
    }

    public LaminarEngine(Consumer<CoalescingBatch<T>> dbExecutor) {
        this(dbExecutor, null, 1000, 10000, 1000, Duration.ofMinutes(10), 0, Duration.ofSeconds(10),
                Duration.ofMillis(100), "laminar-worker-",
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), null);
    }

    public CompletableFuture<Void> dispatch(Mutation<T> mutation) {
        if (!globalSemaphore.tryAcquire()) {
            return CompletableFuture.failedFuture(
                    new java.util.concurrent.RejectedExecutionException(
                            "Global backpressure: System at capacity"));
        }

        if (registry != null) {
            registry.counter("laminar.requests").increment();
        }

        String key = mutation.getEntityKey();
        EntityWorker<T> worker = workers.get(key,
                k -> new EntityWorker<>(k, dbExecutor, maxWaiters, maxBatchSize, maxCoalesceIterations, sharedExecutor,
                        dlq));

        return worker.submit(mutation)
                .whenComplete((result, error) -> globalSemaphore.release());
    }

    public long getActiveWorkerCount() {
        return workers.estimatedSize();
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        long totalWorkers = workers.estimatedSize();
        log.info("LaminarEngine shutting down, draining {} active workers...", totalWorkers);

        long start = System.currentTimeMillis();
        long timeoutMs = shutdownTimeout.toMillis();
        long pollingMs = shutdownPollingInterval.toMillis();

        while (System.currentTimeMillis() - start < timeoutMs) {
            long stillRunning = workers.asMap().values().stream()
                    .filter(EntityWorker::isRunning)
                    .count();

            if (stillRunning == 0) {
                long elapsed = System.currentTimeMillis() - start;
                log.info("All workers drained successfully in {}ms", elapsed);
                workers.invalidateAll();
                return;
            }

            try {
                TimeUnit.MILLISECONDS.sleep(pollingMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Shutdown interrupted with {} workers still active", stillRunning);
                break;
            }
        }

        long remaining = workers.asMap().values().stream()
                .filter(EntityWorker::isRunning)
                .count();

        if (remaining > 0) {
            log.error(
                    "Shutdown timeout ({}ms) exceeded! {} workers still running. Forcing shutdown - data loss possible!",
                    timeoutMs, remaining);
            if (registry != null) {
                registry.counter("laminar.shutdown.forced", "remaining_workers", String.valueOf(remaining)).increment();
            }
        }

        workers.invalidateAll();

        sharedExecutor.shutdown();
        try {
            if (!sharedExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Shared executor did not terminate in 5 seconds, forcing shutdown");
                sharedExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sharedExecutor.shutdownNow();
        }

        log.info("LaminarEngine shutdown complete.");
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Builder for creating a {@link LaminarEngine} instance.
     * <p>
     * Provides a fluent API to configure all aspects of the engine, including:
     * <ul>
     * <li>Loader and Saver functions (Required)</li>
     * <li>Performance tuning (waiters, timeouts, caching)</li>
     * <li>Resilience (backpressure, error handling)</li>
     * <li>Observability (micrometer metrics, thread naming)</li>
     * </ul>
     * </p>
     *
     * @param <T> The type of entity managed by the engine.
     */
    public static class Builder<T> {
        private java.util.function.Function<String, T> loader;
        private Consumer<T> saver;
        private Consumer<Throwable> errorHandler;
        private io.micrometer.core.instrument.MeterRegistry registry;
        private int maxWaiters = 1000;
        private int maxBatchSize = 10000;
        private int maxCoalesceIterations = 1000;
        private Duration timeout = Duration.ofSeconds(30);
        private Duration workerEvictionTime = Duration.ofMinutes(10);
        private long maxCachedWorkers = 0;
        private Duration shutdownTimeout = Duration.ofSeconds(10);
        private Duration shutdownPollingInterval = Duration.ofMillis(100);
        private String threadNamePrefix = "laminar-worker-";
        private DeadLetterQueue<T> dlq;

        /**
         * Sets the function to load an entity from the database.
         * <p>
         * This function is called efficiently on a Virtual Thread.
         * </p>
         *
         * @param loader A function that takes an entity key (String) and returns the
         *               entity.
         * @return this builder
         */
        public Builder<T> loader(java.util.function.Function<String, T> loader) {
            this.loader = loader;
            return this;
        }

        /**
         * Sets the consumer to save the modified entity back to the database.
         * <p>
         * This consumer is called once per batch, after all mutations have been
         * applied.
         * </p>
         *
         * @param saver A consumer that takes the modified entity and persists it.
         * @return this builder
         */
        public Builder<T> saver(Consumer<T> saver) {
            this.saver = saver;
            return this;
        }

        /**
         * Sets an optional error handler to capture exceptions that occur during batch
         * processing.
         *
         * @param errorHandler A consumer that handles errors (e.g., logging, alerting).
         * @return this builder
         */
        public Builder<T> onError(Consumer<Throwable> errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        /**
         * Sets the Micrometer registry for recording metrics.
         *
         * @param registry The Micrometer registry.
         * @return this builder
         */
        public Builder<T> metrics(io.micrometer.core.instrument.MeterRegistry registry) {
            this.registry = registry;
            return this;
        }

        /**
         * Sets the maximum number of pending requests allowed per entity worker.
         * <p>
         * If this limit is exceeded, subsequent requests will fail immediately with
         * {@link java.util.concurrent.RejectedExecutionException}.
         * Default is 1000.
         * </p>
         *
         * @param maxWaiters Max queue size per entity.
         * @return this builder
         */
        public Builder<T> maxWaiters(int maxWaiters) {
            this.maxWaiters = maxWaiters;
            return this;
        }

        /**
         * Sets the timeout for the entire batch execution (load + process + save).
         * <p>
         * Default is 30 seconds.
         * </p>
         *
         * @param timeout The duration to wait before timing out the batch.
         * @return this builder
         */
        public Builder<T> timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets how long an entity worker remains in memory after its last activity.
         * <p>
         * Default is 10 minutes.
         * </p>
         *
         * @param duration The eviction duration.
         * @return this builder
         */
        public Builder<T> workerEvictionTime(Duration duration) {
            this.workerEvictionTime = duration;
            return this;
        }

        /**
         * Sets the maximum number of concurrent entity workers that can be cached in
         * memory.
         * <p>
         * Set to 0 for unbounded (default).
         * </p>
         *
         * @param max The maximum number of workers.
         * @return this builder
         */
        public Builder<T> maxCachedWorkers(long max) {
            this.maxCachedWorkers = max;
            return this;
        }

        /**
         * Sets the grace period for waiting for workers to drain during shutdown.
         * <p>
         * Default is 10 seconds.
         * </p>
         *
         * @param timeout The shutdown timeout.
         * @return this builder
         */
        public Builder<T> shutdownTimeout(Duration timeout) {
            this.shutdownTimeout = timeout;
            return this;
        }

        /**
         * Sets the interval to check worker status during shutdown.
         * <p>
         * Default is 100ms.
         * </p>
         *
         * @param interval The polling interval.
         * @return this builder
         */
        public Builder<T> shutdownPollingInterval(Duration interval) {
            this.shutdownPollingInterval = interval;
            return this;
        }

        /**
         * Sets the prefix for Virtual Thread names created by this engine.
         * <p>
         * Useful for debugging and monitoring. Default is "laminar-worker-".
         * </p>
         *
         * @param prefix The thread name prefix.
         * @return this builder
         */
        public Builder<T> threadNamePrefix(String prefix) {
            this.threadNamePrefix = prefix;
            return this;
        }

        /**
         * Sets the maximum batch size (number of waiters) per entity.
         * <p>
         * Prevents memory exhaustion from unbounded coalescing.
         * Default is 10000.
         * </p>
         *
         * @param maxBatchSize The maximum number of waiters in a batch.
         * @return this builder
         */
        public Builder<T> maxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        /**
         * Sets the maximum number of coalesce iterations before detecting a cycle.
         * <p>
         * Prevents infinite loops if mutation.coalesce(other) returns 'this'
         * indefinitely.
         * Default is 1000.
         * </p>
         *
         * @param maxCoalesceIterations The maximum coalesce iterations.
         * @return this builder
         */
        public Builder<T> maxCoalesceIterations(int maxCoalesceIterations) {
            this.maxCoalesceIterations = maxCoalesceIterations;
            return this;
        }

        /**
         * Sets the Dead Letter Queue for handling failed mutations.
         *
         * @param dlq The DLQ implementation.
         * @return this builder
         */
        public Builder<T> dlq(DeadLetterQueue<T> dlq) {
            this.dlq = dlq;
            return this;
        }

        /**
         * Builds and returns a configured {@link LaminarEngine}.
         *
         * @return The new engine instance.
         * @throws IllegalStateException if loader or saver is missing.
         */
        public LaminarEngine<T> build() {
            if (loader == null || saver == null) {
                throw new IllegalStateException("Loader and Saver are required.");
            }

            java.util.concurrent.ExecutorService executorService = java.util.concurrent.Executors
                    .newVirtualThreadPerTaskExecutor();

            Consumer<CoalescingBatch<T>> safeExecutor = batch -> {
                try {
                    Mutation<T> mutation = batch.getAccumulatedMutation();
                    String key = mutation.getEntityKey();

                    Runnable task = () -> {
                        T entity = loader.apply(key);
                        mutation.apply(entity);
                        saver.accept(entity);
                    };

                    CompletableFuture.runAsync(task, executorService)
                            .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                            .join();

                    if (registry != null) {
                        registry.counter("laminar.batch.process", "entity", mutation.getClass().getSimpleName())
                                .increment();
                    }

                } catch (Throwable t) {
                    if (errorHandler != null) {
                        try {
                            errorHandler.accept(t);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    Throwable cause = t instanceof java.util.concurrent.CompletionException ? t.getCause() : t;
                    if (cause instanceof RuntimeException) {
                        throw (RuntimeException) cause;
                    } else if (cause instanceof Error) {
                        throw (Error) cause;
                    } else {
                        throw new RuntimeException(cause);
                    }
                }
            };

            return new LaminarEngine<>(safeExecutor, registry, maxWaiters, maxBatchSize, maxCoalesceIterations,
                    workerEvictionTime, maxCachedWorkers, shutdownTimeout, shutdownPollingInterval,
                    threadNamePrefix, executorService, dlq);
        }
    }
}
