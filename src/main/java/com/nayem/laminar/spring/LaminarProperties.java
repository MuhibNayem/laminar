package com.nayem.laminar.spring;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Configuration properties for Laminar request coalescing engine.
 * <p>
 * These properties can be configured in {@code application.yml} under the
 * {@code laminar} prefix.
 * </p>
 *
 * @see <a href="https://github.com/nayem/laminar">Laminar Documentation</a>
 */
@ConfigurationProperties(prefix = "laminar")
@Validated
public class LaminarProperties {

    /**
     * Maximum number of pending requests per entity worker before backpressure is
     * applied.
     * When exceeded, new requests will be rejected with a capacity exception.
     */
    @Min(1)
    private int maxWaiters = 1000;

    /**
     * Timeout for load/save operations. Requests exceeding this duration will fail.
     * Format: ISO-8601 duration (e.g., "30s", "5m").
     */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration timeout = Duration.ofSeconds(30);

    /**
     * How long idle entity workers remain cached before eviction.
     * Lower values reduce memory usage; higher values improve performance for
     * bursty traffic.
     */
    @DurationUnit(ChronoUnit.MINUTES)
    private Duration workerEvictionTime = Duration.ofMinutes(10);

    /**
     * Maximum number of entity workers to cache. Set to 0 for unbounded.
     * Useful for limiting memory in high-cardinality scenarios.
     */
    @Min(0)
    private long maxCachedWorkers = 0;

    /**
     * Maximum time to wait for pending mutations to complete during application
     * shutdown.
     */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration shutdownTimeout = Duration.ofSeconds(10);

    /**
     * How frequently to poll for shutdown completion.
     */
    @DurationUnit(ChronoUnit.MILLIS)
    private Duration shutdownPollingInterval = Duration.ofMillis(100);

    /**
     * Prefix for virtual thread names. Useful for monitoring and debugging.
     */
    private String threadNamePrefix = "laminar-worker-";

    /**
     * Maximum mutations per batch. Prevents memory exhaustion from unbounded
     * coalescing.
     */
    @Min(1)
    private int maxBatchSize = 10000;

    /**
     * Maximum coalesce iterations before detecting cyclic coalescing.
     * Prevents infinite loops if coalesce() incorrectly returns 'this'.
     */
    @Min(1)
    private int maxCoalesceIterations = 1000;

    /**
     * Configuration for Saga (multi-entity transactions).
     */
    @Valid
    private Saga saga = new Saga();

    /**
     * Configuration for horizontal scaling via Redis Streams.
     */
    @Valid
    private Cluster cluster = new Cluster();

    /**
     * Configuration for the Dead Letter Queue.
     */
    @Valid
    private Dlq dlq = new Dlq();

    /**
     * Gets the saga configuration.
     * 
     * @return the saga configuration
     */
    public Saga getSaga() {
        return saga;
    }

    /**
     * Sets the saga configuration.
     * 
     * @param saga the saga configuration
     */
    public void setSaga(Saga saga) {
        this.saga = saga;
    }

    /**
     * Configuration for Saga pattern (multi-entity distributed transactions).
     * <p>
     * Sagas provide eventual consistency for operations spanning multiple entities
     * via execute/compensate workflow.
     * </p>
     */
    public static class Saga {
        /**
         * Whether saga orchestration is enabled.
         */
        private boolean enabled = true;

        /**
         * Timeout for individual saga step execution.
         */
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration timeout = Duration.ofSeconds(30);

        /**
         * State persistence backend: 'memory' (development), 'redis' (production).
         */
        private String stateStore = "memory"; // memory, redis, jdbc

        /**
         * Retry configuration for failed saga steps.
         */
        @Valid
        private Retry retry = new Retry();

        /**
         * Crash recovery configuration for interrupted sagas.
         */
        @Valid
        private Recovery recovery = new Recovery();

        /** @return whether saga orchestration is enabled */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled whether saga orchestration is enabled */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return the timeout for individual saga step execution */
        public Duration getTimeout() {
            return timeout;
        }

        /** @param timeout the timeout for individual saga step execution */
        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        /** @return the state persistence backend */
        public String getStateStore() {
            return stateStore;
        }

        /** @param stateStore the state persistence backend */
        public void setStateStore(String stateStore) {
            this.stateStore = stateStore;
        }

        /** @return the retry configuration */
        public Retry getRetry() {
            return retry;
        }

        /** @param retry the retry configuration */
        public void setRetry(Retry retry) {
            this.retry = retry;
        }

        /** @return the recovery configuration */
        public Recovery getRecovery() {
            return recovery;
        }

        /** @param recovery the recovery configuration */
        public void setRecovery(Recovery recovery) {
            this.recovery = recovery;
        }

        /**
         * Retry configuration for failed saga steps.
         * <p>
         * Uses exponential backoff with jitter to prevent thundering herd.
         * </p>
         */
        public static class Retry {
            /**
             * Maximum retry attempts per saga step before failing.
             */
            @Min(1)
            @Max(10)
            private int maxAttempts = 3;

            /**
             * Base backoff duration between retries. Exponential backoff is applied.
             */
            @DurationUnit(ChronoUnit.SECONDS)
            private Duration backoff = Duration.ofSeconds(1);

            /**
             * Random jitter percentage (0.0-1.0) added to backoff to prevent thundering
             * herd.
             */
            @jakarta.validation.constraints.DecimalMin("0.0")
            @jakarta.validation.constraints.DecimalMax("1.0")
            private double jitterPercent = 0.5;

            /** @return the maximum retry attempts */
            public int getMaxAttempts() {
                return maxAttempts;
            }

            /** @param maxAttempts the maximum retry attempts */
            public void setMaxAttempts(int maxAttempts) {
                this.maxAttempts = maxAttempts;
            }

            /** @return the base backoff duration */
            public Duration getBackoff() {
                return backoff;
            }

            /** @param backoff the base backoff duration */
            public void setBackoff(Duration backoff) {
                this.backoff = backoff;
            }

            /** @return the jitter percentage */
            public double getJitterPercent() {
                return jitterPercent;
            }

            /** @param jitterPercent the jitter percentage */
            public void setJitterPercent(double jitterPercent) {
                this.jitterPercent = jitterPercent;
            }
        }

        /**
         * Configuration for automatic crash recovery of interrupted sagas.
         * <p>
         * When enabled, Laminar periodically scans for sagas that were interrupted
         * (e.g., due to application crash) and resumes them.
         * </p>
         */
        public static class Recovery {
            /**
             * Whether automatic crash recovery is enabled.
             */
            private boolean enabled = true;

            /**
             * How frequently to scan for interrupted sagas.
             */
            @DurationUnit(ChronoUnit.SECONDS)
            private Duration interval = Duration.ofSeconds(30);

            /**
             * Whether to use distributed locking for recovery in clustered environments.
             */
            private boolean distributedLocking = false;

            /** @return whether crash recovery is enabled */
            public boolean isEnabled() {
                return enabled;
            }

            /** @param enabled whether crash recovery is enabled */
            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            /** @return the recovery scan interval */
            public Duration getInterval() {
                return interval;
            }

            /** @param interval the recovery scan interval */
            public void setInterval(Duration interval) {
                this.interval = interval;
            }

            /** @return whether distributed locking is enabled */
            public boolean isDistributedLocking() {
                return distributedLocking;
            }

            /** @param distributedLocking whether distributed locking is enabled */
            public void setDistributedLocking(boolean distributedLocking) {
                this.distributedLocking = distributedLocking;
            }
        }
    }

    /**
     * Gets the cluster configuration.
     * 
     * @return the cluster configuration
     */
    public Cluster getCluster() {
        return cluster;
    }

    /**
     * Sets the cluster configuration.
     * 
     * @param cluster the cluster configuration
     */
    public void setCluster(Cluster cluster) {
        this.cluster = cluster;
    }

    /**
     * Configuration for horizontal scaling via Redis Streams.
     * <p>
     * Enables distributed linearization across multiple application instances.
     * Requires {@code spring-boot-starter-data-redis} dependency.
     * </p>
     */
    public static class Cluster {
        /**
         * Whether horizontal scaling via Redis Streams is enabled.
         */
        private boolean enabled = false;

        /**
         * Number of shards for partitioning work. Higher values increase parallelism
         * but require more Redis connections.
         */
        @Min(1)
        @Max(1024)
        private int shards = 16;

        /** @return whether cluster mode is enabled */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled whether cluster mode is enabled */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return the number of shards */
        public int getShards() {
            return shards;
        }

        /** @param shards the number of shards */
        public void setShards(int shards) {
            this.shards = shards;
        }
    }

    /**
     * Configuration for Dead Letter Queue (DLQ).
     * <p>
     * DLQ captures mutations that fail after exhausting all retries,
     * preserving them for manual inspection and replay.
     * </p>
     */
    public static class Dlq {
        /**
         * Whether the Dead Letter Queue is enabled.
         */
        private boolean enabled = false;

        /**
         * Storage backend: 'memory' (development) or 'redis' (production).
         */
        private String store = "memory"; // memory, redis

        /**
         * Maximum retries before a mutation is moved to DLQ.
         */
        @Min(1)
        @Max(10)
        private int maxRetries = 3;

        /**
         * How long DLQ entries are retained before automatic cleanup.
         */
        @DurationUnit(ChronoUnit.DAYS)
        private Duration ttl = Duration.ofDays(7);

        /** @return whether DLQ is enabled */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled whether DLQ is enabled */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return the storage backend */
        public String getStore() {
            return store;
        }

        /** @param store the storage backend */
        public void setStore(String store) {
            this.store = store;
        }

        /** @return the maximum retries before moving to DLQ */
        public int getMaxRetries() {
            return maxRetries;
        }

        /** @param maxRetries the maximum retries before moving to DLQ */
        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        /** @return the retention duration for DLQ entries */
        public Duration getTtl() {
            return ttl;
        }

        /** @param ttl the retention duration for DLQ entries */
        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }
    }

    /**
     * Gets the DLQ configuration.
     * 
     * @return the DLQ configuration
     */
    public Dlq getDlq() {
        return dlq;
    }

    /**
     * Sets the DLQ configuration.
     * 
     * @param dlq the DLQ configuration
     */
    public void setDlq(Dlq dlq) {
        this.dlq = dlq;
    }

    /**
     * Gets the maximum number of pending requests per entity worker.
     * 
     * @return the max waiters value
     */
    public int getMaxWaiters() {
        return maxWaiters;
    }

    /**
     * Sets the maximum number of pending requests per entity worker.
     * 
     * @param maxWaiters the max waiters value
     */
    public void setMaxWaiters(int maxWaiters) {
        this.maxWaiters = maxWaiters;
    }

    /**
     * Gets the timeout for load/save operations.
     * 
     * @return the timeout duration
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Sets the timeout for load/save operations.
     * 
     * @param timeout the timeout duration
     */
    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * Gets the idle worker eviction time.
     * 
     * @return the worker eviction time
     */
    public Duration getWorkerEvictionTime() {
        return workerEvictionTime;
    }

    /**
     * Sets the idle worker eviction time.
     * 
     * @param workerEvictionTime the worker eviction time
     */
    public void setWorkerEvictionTime(Duration workerEvictionTime) {
        this.workerEvictionTime = workerEvictionTime;
    }

    /**
     * Gets the maximum number of cached entity workers.
     * 
     * @return the max cached workers value
     */
    public long getMaxCachedWorkers() {
        return maxCachedWorkers;
    }

    /**
     * Sets the maximum number of cached entity workers.
     * 
     * @param maxCachedWorkers the max cached workers value
     */
    public void setMaxCachedWorkers(long maxCachedWorkers) {
        this.maxCachedWorkers = maxCachedWorkers;
    }

    /**
     * Gets the shutdown timeout duration.
     * 
     * @return the shutdown timeout
     */
    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    /**
     * Sets the shutdown timeout duration.
     * 
     * @param shutdownTimeout the shutdown timeout
     */
    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    /**
     * Gets the shutdown polling interval.
     * 
     * @return the shutdown polling interval
     */
    public Duration getShutdownPollingInterval() {
        return shutdownPollingInterval;
    }

    /**
     * Sets the shutdown polling interval.
     * 
     * @param shutdownPollingInterval the shutdown polling interval
     */
    public void setShutdownPollingInterval(Duration shutdownPollingInterval) {
        this.shutdownPollingInterval = shutdownPollingInterval;
    }

    /**
     * Gets the thread name prefix for virtual threads.
     * 
     * @return the thread name prefix
     */
    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    /**
     * Sets the thread name prefix for virtual threads.
     * 
     * @param threadNamePrefix the thread name prefix
     */
    public void setThreadNamePrefix(String threadNamePrefix) {
        this.threadNamePrefix = threadNamePrefix;
    }

    /**
     * Gets the maximum batch size.
     * 
     * @return the max batch size
     */
    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    /**
     * Sets the maximum batch size.
     * 
     * @param maxBatchSize the max batch size
     */
    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    /**
     * Gets the maximum coalesce iterations.
     * 
     * @return the max coalesce iterations
     */
    public int getMaxCoalesceIterations() {
        return maxCoalesceIterations;
    }

    /**
     * Sets the maximum coalesce iterations.
     * 
     * @param maxCoalesceIterations the max coalesce iterations
     */
    public void setMaxCoalesceIterations(int maxCoalesceIterations) {
        this.maxCoalesceIterations = maxCoalesceIterations;
    }
}
