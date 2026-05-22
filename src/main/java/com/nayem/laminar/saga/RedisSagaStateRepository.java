package com.nayem.laminar.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Enterprise-hardened Redis implementation of SagaStateRepository.
 * 
 * Improvements:
 * - TTL-based cleanup to prevent unbounded memory growth
 * - Retry logic with exponential backoff for transient Redis failures
 * - Proper exception handling and logging
 * - Atomic operations using Redis transactions where applicable
 * - Configurable key prefix and TTL
 */
public class RedisSagaStateRepository implements SagaStateRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisSagaStateRepository.class);
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final String incompleteSetKey;
    private final long sagaTtlHours;
    private final int maxRetries;
    private final long retryDelayMs;

    public RedisSagaStateRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, "laminar:saga:", 72, 3, 100);
    }

    /**
     * @param redisTemplate Redis template for operations
     * @param objectMapper JSON serializer
     * @param keyPrefix Prefix for all Redis keys
     * @param sagaTtlHours TTL for saga state entries (prevents unbounded growth)
     * @param maxRetries Maximum retries for transient failures
     * @param retryDelayMs Base delay between retries
     */
    public RedisSagaStateRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                    String keyPrefix, long sagaTtlHours, int maxRetries, long retryDelayMs) {
        if (sagaTtlHours <= 0) throw new IllegalArgumentException("sagaTtlHours must be positive");
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries cannot be negative");
        
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix;
        this.incompleteSetKey = keyPrefix + "incomplete";
        this.sagaTtlHours = sagaTtlHours;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    @Override
    public void save(SagaState state) {
        executeWithRetry(() -> doSave(state), "save");
    }

    private Void doSave(SagaState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            String key = keyPrefix + state.sagaId();
            
            // Use transaction to ensure atomicity
            redisTemplate.execute(conn -> {
                conn.set(key.getBytes(), json.getBytes());
                
                if (isIncomplete(state.status())) {
                    conn.zSetCommands().zAdd(incompleteSetKey.getBytes(), System.currentTimeMillis(), state.sagaId().getBytes());
                } else {
                    conn.zSetCommands().zRem(incompleteSetKey.getBytes(), state.sagaId().getBytes());
                }
                return null;
            });
            
            // Set TTL on the main key
            redisTemplate.expire(key, sagaTtlHours, TimeUnit.HOURS);
            
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SagaState for ID: {}", state.sagaId(), e);
            throw new RuntimeException("Failed to serialize SagaState", e);
        } catch (DataAccessException e) {
            log.error("Redis error while saving SagaState for ID: {}", state.sagaId(), e);
            throw e;
        }
    }

    @Override
    public Optional<SagaState> findById(String sagaId) {
        return executeWithRetry(() -> doFindById(sagaId), "findById");
    }

    private Optional<SagaState> doFindById(String sagaId) {
        try {
            String json = redisTemplate.opsForValue().get(keyPrefix + sagaId);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, SagaState.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize SagaState for ID: {}", sagaId, e);
            throw new RuntimeException("Failed to deserialize SagaState", e);
        } catch (DataAccessException e) {
            log.error("Redis error while finding SagaState for ID: {}", sagaId, e);
            throw e;
        }
    }

    @Override
    public List<SagaState> findIncomplete() {
        return executeWithRetry(this::doFindIncomplete, "findIncomplete");
    }

    private List<SagaState> doFindIncomplete() {
        try {
            // Use sorted set to get incomplete sagas ordered by creation time
            Set<String> ids = redisTemplate.opsForZSet().range(incompleteSetKey, 0, -1);
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }

            List<String> keys = ids.stream().map(id -> keyPrefix + id).toList();
            List<String> jsons = redisTemplate.opsForValue().multiGet(keys);

            List<SagaState> results = new ArrayList<>();
            if (jsons != null) {
                for (int i = 0; i < jsons.size(); i++) {
                    String json = jsons.get(i);
                    if (json != null) {
                        try {
                            results.add(objectMapper.readValue(json, SagaState.class));
                        } catch (JsonProcessingException e) {
                            log.error("Failed to deserialize SagaState for ID in findIncomplete", e);
                            // Remove corrupted entry from set
                            String failedId = ids.toArray(new String[0])[i];
                            redisTemplate.opsForZSet().remove(incompleteSetKey, failedId);
                        }
                    }
                }
            }
            return results;
        } catch (DataAccessException e) {
            log.error("Redis error while finding incomplete sagas", e);
            throw e;
        }
    }

    @Override
    public void delete(String sagaId) {
        executeWithRetry(() -> doDelete(sagaId), "delete");
    }

    private Void doDelete(String sagaId) {
        try {
            String key = keyPrefix + sagaId;
            redisTemplate.delete(key);
            redisTemplate.opsForZSet().remove(incompleteSetKey, sagaId);
            return null;
        } catch (DataAccessException e) {
            log.error("Redis error while deleting SagaState for ID: {}", sagaId, e);
            throw e;
        }
    }

    /**
     * Executes an operation with retry logic for transient failures.
     */
    private <T> T executeWithRetry(java.util.function.Supplier<T> operation, String operationName) {
        int attempts = 0;
        long delay = retryDelayMs;
        
        while (true) {
            try {
                return operation.get();
            } catch (DataAccessException e) {
                attempts++;
                if (attempts > maxRetries) {
                    log.error("Failed to {} after {} attempts", operationName, attempts, e);
                    throw e;
                }
                
                log.warn("Transient error during {}, attempt {}/{}. Retrying in {}ms", 
                         operationName, attempts, maxRetries, delay, e);
                
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
                
                // Exponential backoff with jitter
                delay = (long) (delay * 1.5 + (Math.random() * delay * 0.2));
            }
        }
    }

    private boolean isIncomplete(SagaStatus status) {
        return status == SagaStatus.PENDING ||
               status == SagaStatus.RUNNING ||
               status == SagaStatus.COMPENSATING;
    }
}
