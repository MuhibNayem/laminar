package com.nayem.laminar.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayem.laminar.core.LaminarDispatcher;
import com.nayem.laminar.core.LaminarEngine;
import com.nayem.laminar.core.Mutation;
import com.nayem.laminar.core.VersionedMutation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dispatches mutations to Redis Streams for clustered deployments.
 * <p>
 * Includes circuit breaker pattern for Redis failure handling.
 * When Redis is unavailable, falls back to local engine dispatch if configured.
 * </p>
 *
 * @param <T> The entity type
 */
public class ClusterDispatcher<T> implements LaminarDispatcher<T> {

    private static final Logger log = LoggerFactory.getLogger(ClusterDispatcher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int shardCount;
    private final Class<T> entityType;

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicReference<Instant> circuitOpenTime = new AtomicReference<>(null);
    private final int failureThreshold;
    private final Duration circuitResetTimeout;

    private volatile LaminarEngine<T> fallbackEngine = null;

    public ClusterDispatcher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
            int shardCount, Class<T> entityType) {
        this(redisTemplate, objectMapper, shardCount, entityType, 5, Duration.ofSeconds(30));
    }

    public ClusterDispatcher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
            int shardCount, Class<T> entityType,
            int failureThreshold, Duration circuitResetTimeout) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.shardCount = shardCount;
        this.entityType = entityType;
        this.failureThreshold = failureThreshold;
        this.circuitResetTimeout = circuitResetTimeout;
    }

    /**
     * Sets a fallback engine to use when Redis is unavailable.
     * If not set, dispatch will fail when circuit is open.
     *
     * @param fallbackEngine The local LaminarEngine to use as fallback
     */
    public void setFallbackEngine(LaminarEngine<T> fallbackEngine) {
        this.fallbackEngine = fallbackEngine;
    }

    @Override
    public CompletableFuture<Void> dispatch(Mutation<T> mutation) {
        if (isCircuitOpen()) {
            Instant openTime = circuitOpenTime.get();
            if (openTime != null && Instant.now().isAfter(openTime.plus(circuitResetTimeout))) {
                log.info("Circuit breaker attempting reset for entity type: {}", entityType.getSimpleName());
                circuitOpenTime.set(null);
                failureCount.set(0);
            } else {
                return dispatchWithFallback(mutation, "Circuit breaker is open");
            }
        }

        try {
            int shard = Math.abs(mutation.getEntityKey().hashCode() % shardCount);
            String streamKey = "laminar:stream:" + entityType.getName() + ":" + shard;

            MutationEnvelope envelope = new MutationEnvelope();
            envelope.setPayload(objectMapper.writeValueAsString(mutation));
            envelope.setMutationClass(mutation.getClass().getName());
            envelope.setEntityKey(mutation.getEntityKey());

            if (mutation instanceof VersionedMutation versioned) {
                envelope.setVersion(versioned.getVersion());
            } else {
                envelope.setVersion(1);
            }

            String jsonPayload = objectMapper.writeValueAsString(envelope);

            CompletableFuture<Void> future = new CompletableFuture<>();

            CompletableFuture.runAsync(() -> {
                try {
                    redisTemplate.opsForStream().add(
                            StreamRecords.newRecord()
                                    .in(streamKey)
                                    .ofStrings(java.util.Map.of("data", jsonPayload)));
                    failureCount.set(0);
                    future.complete(null);
                } catch (RedisConnectionFailureException e) {
                    handleRedisFailure(e);
                    dispatchWithFallback(mutation, e.getMessage())
                            .whenComplete((v, ex) -> {
                                if (ex != null) {
                                    future.completeExceptionally(ex);
                                } else {
                                    future.complete(null);
                                }
                            });
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            return future;

        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private boolean isCircuitOpen() {
        return circuitOpenTime.get() != null;
    }

    private void handleRedisFailure(Exception e) {
        int failures = failureCount.incrementAndGet();
        if (failures >= failureThreshold) {
            log.error("Circuit breaker OPEN for entity type {} after {} failures: {}",
                    entityType.getSimpleName(), failures, e.getMessage());
            circuitOpenTime.set(Instant.now());
        } else {
            log.warn("Redis failure {} of {}: {}", failures, failureThreshold, e.getMessage());
        }
    }

    private CompletableFuture<Void> dispatchWithFallback(Mutation<T> mutation, String reason) {
        if (fallbackEngine != null) {
            log.warn("Using LOCAL fallback for entity {} ({}). Key: {}",
                    entityType.getSimpleName(), reason, mutation.getEntityKey());
            return fallbackEngine.dispatch(mutation);
        } else {
            return CompletableFuture.failedFuture(
                    new RedisConnectionFailureException(
                            "Redis unavailable and no fallback engine configured: " + reason));
        }
    }

    /**
     * Returns true if the circuit breaker is currently open.
     * Useful for health checks and monitoring.
     */
    public boolean isCircuitBreakerOpen() {
        return isCircuitOpen();
    }

    /**
     * Returns the current failure count.
     * Useful for monitoring.
     */
    public int getCurrentFailureCount() {
        return failureCount.get();
    }

    /**
     * Manually closes the circuit breaker.
     * Use with caution - typically for admin/recovery scenarios.
     */
    public void resetCircuitBreaker() {
        log.info("Manually resetting circuit breaker for entity type: {}", entityType.getSimpleName());
        circuitOpenTime.set(null);
        failureCount.set(0);
    }
}
