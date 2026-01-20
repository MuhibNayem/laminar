package com.nayem.laminar.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayem.laminar.core.LaminarDispatcher;
import com.nayem.laminar.core.Mutation;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CompletableFuture;

public class ClusterDispatcher<T> implements LaminarDispatcher<T> {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int shardCount;
    private final Class<T> entityType;

    public ClusterDispatcher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, int shardCount, Class<T> entityType) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.shardCount = shardCount;
        this.entityType = entityType;
    }

    @Override
    public CompletableFuture<Void> dispatch(Mutation<T> mutation) {
        try {
            int shard = Math.abs(mutation.getEntityKey().hashCode() % shardCount);
            String streamKey = "laminar:stream:" + entityType.getName() + ":" + shard;

            MutationEnvelope envelope = new MutationEnvelope();
            envelope.setPayload(objectMapper.writeValueAsString(mutation));
            envelope.setMutationClass(mutation.getClass().getName());
            envelope.setEntityKey(mutation.getEntityKey());

            String jsonPayload = objectMapper.writeValueAsString(envelope);
            
            return CompletableFuture.supplyAsync(() -> {
                redisTemplate.opsForStream().add(
                        StreamRecords.newRecord()
                                .in(streamKey)
                                .ofStrings(java.util.Map.of("data", jsonPayload))
                );
                return null;
            });

        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
