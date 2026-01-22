package com.nayem.laminar.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayem.laminar.core.LaminarEngine;
import com.nayem.laminar.core.Mutation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Redis failover scenarios using Testcontainers.
 * Tests cluster dispatcher behavior when Redis becomes unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
public class RedisFailoverTest {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private StringRedisTemplate redisTemplate;
    private ObjectMapper objectMapper;
    private Map<String, AtomicInteger> dataStore;
    private LaminarEngine<AtomicInteger> engine;

    @BeforeEach
    void setUp() {
        dataStore = new ConcurrentHashMap<>();
        objectMapper = new ObjectMapper();

        // Set up Redis connection
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                redis.getHost(), redis.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);

        // Set up local engine
        engine = LaminarEngine.<AtomicInteger>builder()
                .loader(key -> dataStore.computeIfAbsent(key, k -> new AtomicInteger(0)))
                .saver(entity -> {
                })
                .timeout(Duration.ofSeconds(5))
                .maxWaiters(1000)
                .build();
    }

    /**
     * Tests that ClusterDispatcher can successfully dispatch when Redis is
     * available.
     */
    @Test
    void testClusterDispatcherWithHealthyRedis() throws Exception {
        ClusterDispatcher<AtomicInteger> dispatcher = new ClusterDispatcher<>(
                redisTemplate, objectMapper, 16, AtomicInteger.class);

        CompletableFuture<Void> result = dispatcher.dispatch(new TestMutation("entity1", 10));

        assertDoesNotThrow(() -> result.get(5, TimeUnit.SECONDS));

        // Verify mutation was written to Redis stream
        String streamKey = "laminar:stream:" + AtomicInteger.class.getName() + ":";
        // Hash of "entity1" mod 16 = shard number
        boolean foundInStream = false;
        for (int shard = 0; shard < 16; shard++) {
            Long size = redisTemplate.opsForStream().size(streamKey + shard);
            if (size != null && size > 0) {
                foundInStream = true;
                break;
            }
        }
        assertTrue(foundInStream, "Mutation should be written to Redis stream");
    }

    /**
     * Tests that local engine continues to work when Redis is unavailable.
     * This validates the fallback path.
     */
    @Test
    void testLocalEngineFallbackWhenRedisDown() throws Exception {
        // Stop Redis container
        redis.stop();

        // Local engine should still work
        dataStore.put("entity1", new AtomicInteger(0));

        CompletableFuture<Void> result = engine.dispatch(new TestMutation("entity1", 10));
        result.get(5, TimeUnit.SECONDS);

        assertEquals(10, dataStore.get("entity1").get());

        // Restart Redis for other tests
        redis.start();
    }

    /**
     * Tests Redis connection recovery after temporary failure.
     */
    @Test
    void testRedisReconnectionAfterFailure() throws Exception {
        ClusterDispatcher<AtomicInteger> dispatcher = new ClusterDispatcher<>(
                redisTemplate, objectMapper, 16, AtomicInteger.class);

        // First dispatch should succeed
        CompletableFuture<Void> first = dispatcher.dispatch(new TestMutation("entity1", 10));
        assertDoesNotThrow(() -> first.get(5, TimeUnit.SECONDS));

        // Stop and restart Redis
        redis.stop();
        Thread.sleep(100);
        redis.start();
        Thread.sleep(500); // Wait for container to be ready

        // Update connection factory to new port
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                redis.getHost(), redis.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        StringRedisTemplate newTemplate = new StringRedisTemplate(factory);

        ClusterDispatcher<AtomicInteger> newDispatcher = new ClusterDispatcher<>(
                newTemplate, objectMapper, 16, AtomicInteger.class);

        // Dispatch after reconnection should succeed
        CompletableFuture<Void> after = newDispatcher.dispatch(new TestMutation("entity2", 20));
        assertDoesNotThrow(() -> after.get(5, TimeUnit.SECONDS));
    }

    // Test mutation implementation
    static class TestMutation implements Mutation<AtomicInteger> {
        private final String entityKey;
        private final int amount;

        public TestMutation(String entityKey, int amount) {
            this.entityKey = entityKey;
            this.amount = amount;
        }

        @Override
        public String getEntityKey() {
            return entityKey;
        }

        @Override
        public Mutation<AtomicInteger> coalesce(Mutation<AtomicInteger> other) {
            if (other instanceof TestMutation next) {
                return new TestMutation(entityKey, this.amount + next.amount);
            }
            return other;
        }

        @Override
        public void apply(AtomicInteger entity) {
            entity.addAndGet(amount);
        }

        // Required for Jackson serialization
        public int getAmount() {
            return amount;
        }
    }
}
