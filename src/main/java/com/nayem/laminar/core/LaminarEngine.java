package com.nayem.laminar.core;

import com.nayem.laminar.worker.EntityWorker;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class LaminarEngine<T> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LaminarEngine.class);

    private final com.github.benmanes.caffeine.cache.Cache<String, EntityWorker<T>> workers;
    private final Consumer<CoalescingBatch<T>> dbExecutor;
    private final io.micrometer.core.instrument.MeterRegistry registry;
    private final int maxWaiters;
    private final Duration shutdownTimeout;
    private final Duration shutdownPollingInterval;
    private final String threadNamePrefix;
    private final java.util.concurrent.ExecutorService sharedExecutor;
    private final java.util.concurrent.Semaphore globalSemaphore;

    public LaminarEngine(Consumer<CoalescingBatch<T>> dbExecutor,
            io.micrometer.core.instrument.MeterRegistry registry,
            int maxWaiters,
            Duration workerEvictionTime,
            long maxCachedWorkers,
            Duration shutdownTimeout,
            Duration shutdownPollingInterval,
            String threadNamePrefix,
            java.util.concurrent.ExecutorService sharedExecutor) {
        this.dbExecutor = dbExecutor;
        this.registry = registry;
        this.maxWaiters = maxWaiters;
        this.shutdownTimeout = shutdownTimeout;
        this.shutdownPollingInterval = shutdownPollingInterval;
        this.threadNamePrefix = threadNamePrefix;
        this.sharedExecutor = sharedExecutor;
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
        this(dbExecutor, null, 1000, Duration.ofMinutes(10), 0, Duration.ofSeconds(10), Duration.ofMillis(100),
                "laminar-worker-", java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    public CompletableFuture<Void> dispatch(Mutation<T> mutation) {
        // Try to acquire global permit
        if (!globalSemaphore.tryAcquire()) {
            return CompletableFuture.failedFuture(
                    new java.util.concurrent.RejectedExecutionException(
                            "Global backpressure: System at capacity"));
        }

        if (registry != null) {
            registry.counter("laminar.requests").increment();
        }

        String key = mutation.getEntityKey();
        EntityWorker<T> worker = workers.get(key, k -> new EntityWorker<>(k, dbExecutor, maxWaiters, threadNamePrefix));

        return worker.submit(mutation)
                .whenComplete((result, error) -> globalSemaphore.release());
    }

    public long getActiveWorkerCount() {
        return workers.estimatedSize();
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

        // Shutdown shared executor to prevent resource leak
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

    public static class Builder<T> {
        private java.util.function.Function<String, T> loader;
        private Consumer<T> saver;
        private Consumer<Throwable> errorHandler;
        private io.micrometer.core.instrument.MeterRegistry registry;
        private int maxWaiters = 1000;
        private Duration timeout = Duration.ofSeconds(30);
        private Duration workerEvictionTime = Duration.ofMinutes(10);
        private long maxCachedWorkers = 0;
        private Duration shutdownTimeout = Duration.ofSeconds(10);
        private Duration shutdownPollingInterval = Duration.ofMillis(100);
        private String threadNamePrefix = "laminar-worker-";

        public Builder<T> loader(java.util.function.Function<String, T> loader) {
            this.loader = loader;
            return this;
        }

        public Builder<T> saver(Consumer<T> saver) {
            this.saver = saver;
            return this;
        }

        public Builder<T> onError(Consumer<Throwable> errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        public Builder<T> metrics(io.micrometer.core.instrument.MeterRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder<T> maxWaiters(int maxWaiters) {
            this.maxWaiters = maxWaiters;
            return this;
        }

        public Builder<T> timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder<T> workerEvictionTime(Duration duration) {
            this.workerEvictionTime = duration;
            return this;
        }

        public Builder<T> maxCachedWorkers(long max) {
            this.maxCachedWorkers = max;
            return this;
        }

        public Builder<T> shutdownTimeout(Duration timeout) {
            this.shutdownTimeout = timeout;
            return this;
        }

        public Builder<T> shutdownPollingInterval(Duration interval) {
            this.shutdownPollingInterval = interval;
            return this;
        }

        public Builder<T> threadNamePrefix(String prefix) {
            this.threadNamePrefix = prefix;
            return this;
        }

        public LaminarEngine<T> build() {
            if (loader == null || saver == null) {
                throw new IllegalStateException("Loader and Saver are required.");
            }

            // Create shared executor for this engine instance
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

            return new LaminarEngine<>(safeExecutor, registry, maxWaiters, workerEvictionTime,
                    maxCachedWorkers, shutdownTimeout, shutdownPollingInterval, threadNamePrefix, executorService);
        }
    }
}
