package com.nayem.laminar.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayem.laminar.core.LaminarEngine;
import com.nayem.laminar.core.Mutation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages Redis Stream consumers for clustered deployments.
 * <p>
 * Includes graceful error handling with exponential backoff for Redis failures.
 * </p>
 *
 * @param <T> The entity type
 */
public class ClusterWorkerManager<T> implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ClusterWorkerManager.class);

    private final LaminarEngine<T> engine;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int shardCount;
    private final Class<T> entityType;
    private final ExecutorService executor;

    private final ConcurrentHashMap<Integer, Boolean> activeShards = new ConcurrentHashMap<>();
    private volatile boolean isRunning = true;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private static final int MAX_BACKOFF_SECONDS = 60;
    private static final int BASE_BACKOFF_SECONDS = 1;

    public ClusterWorkerManager(LaminarEngine<T> engine, StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
            int shardCount, Class<T> entityType) {
        this.engine = engine;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.shardCount = shardCount;
        this.entityType = entityType;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void start() {
        Thread.ofVirtual().name("laminar-cluster-manager-" + entityType.getSimpleName()).start(this::manageShards);
    }

    private void manageShards() {
        while (isRunning) {
            try {
                for (int i = 0; i < shardCount; i++) {
                    final int shardId = i;
                    String lockKey = "laminar:lock:" + entityType.getName() + ":" + shardId;

                    Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked",
                            Duration.ofSeconds(10));

                    if (Boolean.TRUE.equals(acquired)) {
                        if (activeShards.putIfAbsent(shardId, true) == null) {
                            log.info("Acquired ownership of shard {} for {}", shardId, entityType.getSimpleName());
                            startShardConsumer(shardId);
                        }
                        redisTemplate.expire(lockKey, Duration.ofSeconds(10));
                    } else {
                        activeShards.remove(shardId);
                    }
                }
                consecutiveFailures.set(0);
                TimeUnit.SECONDS.sleep(5);
            } catch (RedisConnectionFailureException e) {
                handleRedisFailure(e, "shard management");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Unexpected error in shard management", e);
                sleepWithBackoff();
            }
        }
    }

    private void startShardConsumer(int shardId) {
        executor.submit(() -> {
            String streamKey = "laminar:stream:" + entityType.getName() + ":" + shardId;
            String groupName = "laminar-group";
            String consumerName = "consumer-" + java.util.UUID.randomUUID();

            try {
                redisTemplate.opsForStream().createGroup(streamKey, groupName);
            } catch (Exception e) {
                log.debug("Group already exists for {}: {}", streamKey, e.getMessage());
            }

            while (activeShards.containsKey(shardId) && isRunning) {
                try {
                    List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                            org.springframework.data.redis.connection.stream.Consumer.from(groupName, consumerName),
                            StreamReadOptions.empty().block(Duration.ofMillis(2000)).count(10),
                            StreamOffset.create(streamKey,
                                    org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()));

                    if (records != null) {
                        for (MapRecord<String, Object, Object> record : records) {
                            processRecord(record, streamKey, groupName);
                        }
                    }
                    consecutiveFailures.set(0);
                } catch (RedisConnectionFailureException e) {
                    handleRedisFailure(e, "shard " + shardId + " consumer");
                    if (!isRunning)
                        break;
                } catch (Exception e) {
                    log.error("Error processing shard " + shardId, e);
                    sleepWithBackoff();
                }
            }
            log.info("Stopped consumer for shard {}", shardId);
        });
    }

    private void processRecord(MapRecord<String, Object, Object> record, String streamKey, String groupName) {
        String json = (String) record.getValue().get("data");
        if (json == null) {
            redisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
            return;
        }

        try {
            MutationEnvelope envelope = objectMapper.readValue(json, MutationEnvelope.class);
            String className = envelope.getMutationClass();

            Class<?> clazz;
            try {
                clazz = Class.forName(className, false, getClass().getClassLoader());
                if (!Mutation.class.isAssignableFrom(clazz)) {
                    log.error("Security violation: Blocked attempt to deserialize non-Mutation class {}", className);
                    redisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
                    return;
                }
            } catch (ClassNotFoundException e) {
                log.error("Unknown mutation class: {}", className);
                redisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
                return;
            }

            @SuppressWarnings("unchecked")
            Mutation<T> mutation = (Mutation<T>) objectMapper.readValue(envelope.getPayload(), clazz);

            java.util.concurrent.CompletableFuture<Void> persistenceFuture = engine.dispatch(mutation);

            try {
                persistenceFuture.join();
                redisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
            } catch (Exception e) {
                log.error("Failed to persist mutation for key {}, will retry", mutation.getEntityKey(), e);
            }
        } catch (Exception e) {
            log.error("Failed to process record", e);
        }
    }

    private void handleRedisFailure(RedisConnectionFailureException e, String context) {
        int failures = consecutiveFailures.incrementAndGet();
        log.warn("Redis connection failure in {} (attempt {}): {}", context, failures, e.getMessage());
        sleepWithBackoff();
    }

    private void sleepWithBackoff() {
        int failures = consecutiveFailures.get();
        int backoffSeconds = Math.min(BASE_BACKOFF_SECONDS * (1 << Math.min(failures, 6)), MAX_BACKOFF_SECONDS);
        log.debug("Backing off for {} seconds", backoffSeconds);
        try {
            TimeUnit.SECONDS.sleep(backoffSeconds);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        isRunning = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate within 10 seconds, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /**
     * Returns the current consecutive failure count.
     * Useful for monitoring.
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * Returns true if the manager is currently running.
     */
    public boolean isRunning() {
        return isRunning;
    }
}
