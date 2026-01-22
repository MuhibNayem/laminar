package com.nayem.laminar.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class RedisSagaStateRepository implements SagaStateRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisSagaStateRepository.class);
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String KEY_PREFIX = "laminar:saga:";
    private static final String INCOMPLETE_SET_KEY = "laminar:sagas:incomplete";

    public RedisSagaStateRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(SagaState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(KEY_PREFIX + state.sagaId(), json);

            if (isIncomplete(state.status())) {
                redisTemplate.opsForSet().add(INCOMPLETE_SET_KEY, state.sagaId());
            } else {
                redisTemplate.opsForSet().remove(INCOMPLETE_SET_KEY, state.sagaId());
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize SagaState", e);
        }
    }

    @Override
    public Optional<SagaState> findById(String sagaId) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + sagaId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, SagaState.class));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize SagaState", e);
        }
    }

    @Override
    public List<SagaState> findIncomplete() {
        Set<String> ids = redisTemplate.opsForSet().members(INCOMPLETE_SET_KEY);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        List<String> keys = ids.stream().map(id -> KEY_PREFIX + id).toList();
        List<String> jsons = redisTemplate.opsForValue().multiGet(keys);

        List<SagaState> results = new ArrayList<>();
        if (jsons != null) {
            for (String json : jsons) {
                if (json != null) {
                    try {
                        results.add(objectMapper.readValue(json, SagaState.class));
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize SagaState for ID in findIncomplete", e);
                    }
                }
            }
        }
        return results;
    }

    @Override
    public void delete(String sagaId) {
        redisTemplate.delete(KEY_PREFIX + sagaId);
        redisTemplate.opsForSet().remove(INCOMPLETE_SET_KEY, sagaId);
    }

    private boolean isIncomplete(SagaStatus status) {
        return status == SagaStatus.PENDING ||
               status == SagaStatus.RUNNING ||
               status == SagaStatus.COMPENSATING;
    }
}
