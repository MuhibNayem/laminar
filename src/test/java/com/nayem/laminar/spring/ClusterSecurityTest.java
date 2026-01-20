package com.nayem.laminar.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayem.laminar.core.LaminarEngine;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ClusterSecurityTest {

    @Test
    void shouldRejectArbitraryClassDeserialization() throws Exception {
        // Mock dependencies
        LaminarEngine<Object> engine = mock(LaminarEngine.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();

        StreamOperations streamOps = mock(StreamOperations.class);
        ValueOperations valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // Simulate lock acquisition
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        // Prepare Malicious Payload
        MutationEnvelope envelope = new MutationEnvelope();
        envelope.setEntityKey("test-key");
        envelope.setMutationClass("java.lang.String");
        envelope.setPayload("\"dangerous-payload\"");

        String json = objectMapper.writeValueAsString(envelope);

        // Fix Generics: Map<String, Object> keys, values
        // MapRecord.create takes stream key, then Map.
        Map<Object, Object> data = Map.of("data", json);

        MapRecord<String, Object, Object> record = MapRecord.create("stream", data).withId(RecordId.of("1-0"));

        // Disambiguate read method: explicitly use Consumer.class
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(Collections.singletonList(record))
                .thenAnswer(invocation -> {
                    Thread.sleep(100);
                    return Collections.emptyList();
                });

        ClusterWorkerManager<Object> manager = new ClusterWorkerManager<>(
                engine, redisTemplate, objectMapper, 1, Object.class);

        manager.start();

        // Give it time to process
        Thread.sleep(500);

        manager.shutdown();

        // VERIFICATION:
        // 1. Engine should NOT have received any dispatch
        verify(engine, never()).dispatch(any());

        // 2. Message should still be ACKed (we acknowledge invalid messages to skip
        // them)
        verify(streamOps).acknowledge(anyString(), anyString(), eq(RecordId.of("1-0")));
    }
}
