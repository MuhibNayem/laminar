package com.nayem.laminar.dlq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayem.laminar.core.Mutation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis-backed implementation of {@link DeadLetterQueue}.
 * <p>
 * Stores DLQ entries in a Redis list for durability across restarts.
 * Suitable for clustered/distributed deployments.
 * </p>
 *
 * @param <T> The entity type
 */
public class RedisDeadLetterQueue<T> implements DeadLetterQueue<T> {

    private static final Logger log = LoggerFactory.getLogger(RedisDeadLetterQueue.class);
    private static final String DLQ_KEY_PREFIX = "laminar:dlq:";
    private static final String DLQ_INDEX_KEY_PREFIX = "laminar:dlq:index:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String entityType;
    private final Duration entryTtl;

    public RedisDeadLetterQueue(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
            Class<T> entityType, Duration entryTtl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.entityType = entityType.getName();
        this.entryTtl = entryTtl;
    }

    private String queueKey() {
        return DLQ_KEY_PREFIX + entityType;
    }

    private String indexKey(String entryId) {
        return DLQ_INDEX_KEY_PREFIX + entityType + ":" + entryId;
    }

    @Override
    public void send(DlqEntry<T> entry) {
        try {
            DlqEnvelope envelope = toEnvelope(entry);
            String json = objectMapper.writeValueAsString(envelope);

            redisTemplate.opsForList().rightPush(queueKey(), json);
            redisTemplate.opsForValue().set(indexKey(entry.id()), json, entryTtl);

            log.warn("Mutation sent to Redis DLQ: entityKey={}, error={}, retryCount={}",
                    entry.entityKey(), entry.errorMessage(), entry.retryCount());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize DLQ entry: {}", entry.id(), e);
        }
    }

    @Override
    public Optional<DlqEntry<T>> peek() {
        String json = redisTemplate.opsForList().index(queueKey(), 0);
        return deserialize(json);
    }

    @Override
    public Optional<DlqEntry<T>> poll() {
        String json = redisTemplate.opsForList().leftPop(queueKey());
        Optional<DlqEntry<T>> entry = deserialize(json);
        entry.ifPresent(e -> redisTemplate.delete(indexKey(e.id())));
        return entry;
    }

    @Override
    public void acknowledge(String entryId) {
        String json = redisTemplate.opsForValue().get(indexKey(entryId));
        if (json != null) {
            redisTemplate.opsForList().remove(queueKey(), 1, json);
            redisTemplate.delete(indexKey(entryId));
            log.info("Redis DLQ entry acknowledged: id={}", entryId);
        }
    }

    @Override
    public long size() {
        Long size = redisTemplate.opsForList().size(queueKey());
        return size != null ? size : 0;
    }

    @Override
    public List<DlqEntry<T>> list(int limit) {
        List<String> entries = redisTemplate.opsForList().range(queueKey(), 0, limit - 1);
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
                .map(this::deserialize)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    private Optional<DlqEntry<T>> deserialize(String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            DlqEnvelope envelope = objectMapper.readValue(json, DlqEnvelope.class);
            return Optional.of(fromEnvelope(envelope));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize DLQ entry", e);
            return Optional.empty();
        }
    }

    private DlqEnvelope toEnvelope(DlqEntry<T> entry) throws JsonProcessingException {
        return new DlqEnvelope(
                entry.id(),
                objectMapper.writeValueAsString(entry.mutation()),
                entry.mutation().getClass().getName(),
                entry.entityKey(),
                entry.errorMessage(),
                entry.errorClass(),
                entry.retryCount(),
                entry.timestamp().toEpochMilli(),
                entry.lastRetryTimestamp() != null ? entry.lastRetryTimestamp().toEpochMilli() : null);
    }

    @SuppressWarnings("unchecked")
    private DlqEntry<T> fromEnvelope(DlqEnvelope envelope) throws JsonProcessingException {
        Class<?> mutationClass;
        try {
            mutationClass = Class.forName(envelope.mutationClass());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Unknown mutation class: " + envelope.mutationClass(), e);
        }

        Mutation<T> mutation = (Mutation<T>) objectMapper.readValue(envelope.mutationPayload(), mutationClass);

        return new DlqEntry<>(
                envelope.id(),
                mutation,
                envelope.entityKey(),
                envelope.errorMessage(),
                envelope.errorClass(),
                envelope.retryCount(),
                Instant.ofEpochMilli(envelope.timestamp()),
                envelope.lastRetryTimestamp() != null ? Instant.ofEpochMilli(envelope.lastRetryTimestamp()) : null);
    }

    /**
     * Envelope for serializing DLQ entries to Redis.
     */
    record DlqEnvelope(
            String id,
            String mutationPayload,
            String mutationClass,
            String entityKey,
            String errorMessage,
            String errorClass,
            int retryCount,
            long timestamp,
            Long lastRetryTimestamp) {
    }
}
