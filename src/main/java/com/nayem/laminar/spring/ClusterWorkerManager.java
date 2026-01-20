package com.nayem.laminar.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayem.laminar.core.LaminarEngine;
import com.nayem.laminar.core.Mutation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            for (int i = 0; i < shardCount; i++) {
                final int shardId = i;
                String lockKey = "laminar:lock:" + entityType.getName() + ":" + shardId;

                // Try to acquire lock for 10 seconds
                Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", Duration.ofSeconds(10));

                if (Boolean.TRUE.equals(acquired)) {
                    if (activeShards.putIfAbsent(shardId, true) == null) {
                        log.info("Acquired ownership of shard {} for {}", shardId, entityType.getSimpleName());
                        startShardConsumer(shardId);
                    }
                    // Renew lock
                    redisTemplate.expire(lockKey, Duration.ofSeconds(10));
                } else {
                    // Lost lock
                    activeShards.remove(shardId);
                }
            }
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
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
                // Group might already exist
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
                            String json = (String) record.getValue().get("data");
                            if (json == null) {
                                // Fallback or skip
                                redisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
                                continue;
                            }

                            MutationEnvelope envelope = objectMapper.readValue(json, MutationEnvelope.class);
                            String className = envelope.getMutationClass();

                            // Security: Validate class is a Mutation before loading
                            Class<?> clazz;
                            try {
                                clazz = Class.forName(className, false, getClass().getClassLoader());
                                if (!Mutation.class.isAssignableFrom(clazz)) {
                                    log.error(
                                            "Security violation: Blocked attempt to deserialize non-Mutation class {}",
                                            className);
                                    redisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
                                    continue;
                                }
                            } catch (ClassNotFoundException e) {
                                log.error("Unknown mutation class: {}", className);
                                redisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
                                continue;
                            }

                            Mutation<T> mutation = (Mutation<T>) objectMapper.readValue(envelope.getPayload(), clazz);

                            // Dispatch locally and track completion
                            java.util.concurrent.CompletableFuture<Void> persistenceFuture = engine.dispatch(mutation);

                            // Block until persisted locally to ensure strict durability (At-Least-Once)
                            try {
                                persistenceFuture.join();
                                redisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
                            } catch (Exception e) {
                                log.error("Failed to persist mutation for key " + mutation.getEntityKey()
                                        + ", will retry", e);
                                // Do NOT ack - Redis will redeliver to this or another consumer
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Error processing shard " + shardId, e);
                    try {
                        TimeUnit.SECONDS.sleep(1);
                    } catch (InterruptedException ex) {
                        break;
                    }
                }
            }
            log.info("Stopped consumer for shard {}", shardId);
        });
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        isRunning = false;
        executor.shutdown();
    }
}
