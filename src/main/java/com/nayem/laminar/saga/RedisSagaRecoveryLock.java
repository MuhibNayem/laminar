package com.nayem.laminar.saga;

import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;

public class RedisSagaRecoveryLock implements SagaRecoveryLock {
    private final StringRedisTemplate redisTemplate;
    private static final String LOCK_PREFIX = "laminar:saga:lock:";

    public RedisSagaRecoveryLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean acquireLock(String lockKey, long lockDurationMs) {
        String key = LOCK_PREFIX + lockKey;
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, "locked", Duration.ofMillis(lockDurationMs));
        return success != null && success;
    }

    @Override
    public void releaseLock(String lockKey) {
        redisTemplate.delete(LOCK_PREFIX + lockKey);
    }
}