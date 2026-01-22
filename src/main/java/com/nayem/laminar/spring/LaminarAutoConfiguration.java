package com.nayem.laminar.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayem.laminar.core.LaminarDispatcher;
import com.nayem.laminar.core.LaminarEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import com.nayem.laminar.dlq.DeadLetterQueue;
import com.nayem.laminar.dlq.InMemoryDeadLetterQueue;
import com.nayem.laminar.dlq.RedisDeadLetterQueue;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@EnableAspectJAutoProxy
@org.springframework.boot.context.properties.EnableConfigurationProperties(LaminarProperties.class)
public class LaminarAutoConfiguration {

    @Bean
    public LaminarRegistry laminarRegistry(List<LaminarHandler<?>> handlers,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registryProvider,
            LaminarProperties properties,
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            org.springframework.beans.factory.ObjectProvider<ObjectMapper> objectMapperProvider) {

        java.util.List<AutoCloseable> resources = new java.util.ArrayList<>();

        Map<Class<?>, LaminarDispatcher<?>> dispatchers = handlers.stream()
                .collect(Collectors.toMap(
                        LaminarHandler::getEntityType,
                        handler -> {
                            @SuppressWarnings("unchecked")
                            LaminarHandler<Object> objectHandler = (LaminarHandler<Object>) handler;

                            LaminarEngine.Builder<Object> builder = LaminarEngine.<Object>builder()
                                    .loader(objectHandler::load)
                                    .saver(objectHandler::save)
                                    .metrics(registryProvider.getIfAvailable())
                                    .maxWaiters(properties.getMaxWaiters())
                                    .maxBatchSize(properties.getMaxBatchSize())
                                    .maxCoalesceIterations(properties.getMaxCoalesceIterations())
                                    .timeout(properties.getTimeout())
                                    .workerEvictionTime(properties.getWorkerEvictionTime())
                                    .maxCachedWorkers(properties.getMaxCachedWorkers())
                                    .shutdownTimeout(properties.getShutdownTimeout())
                                    .shutdownPollingInterval(properties.getShutdownPollingInterval())
                                    .threadNamePrefix(properties.getThreadNamePrefix());

                            if (properties.getDlq().isEnabled()) {
                                DeadLetterQueue<Object> dlq;
                                String store = properties.getDlq().getStore();
                                if ("redis".equalsIgnoreCase(store)) {
                                    StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
                                    ObjectMapper mapper = objectMapperProvider.getIfAvailable();
                                    if (redis == null || mapper == null) {
                                        throw new IllegalStateException(
                                                "Redis and ObjectMapper are required for Redis DLQ");
                                    }
                                    dlq = new RedisDeadLetterQueue<>(redis, mapper,
                                            (Class<Object>) handler.getEntityType(),
                                            properties.getDlq().getTtl());
                                } else {
                                    dlq = new InMemoryDeadLetterQueue<>();
                                }
                                builder.dlq(dlq);
                            }

                            LaminarEngine<?> builtEngine = builder.build();
                            resources.add(builtEngine);

                            if (properties.getCluster().isEnabled()) {
                                StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
                                ObjectMapper mapper = objectMapperProvider.getIfAvailable();
                                if (redis == null || mapper == null) {
                                    throw new IllegalStateException(
                                            "Redis and ObjectMapper are required for Laminar Cluster Mode");
                                }

                                @SuppressWarnings("rawtypes")
                                ClusterWorkerManager manager = new ClusterWorkerManager(
                                        builtEngine,
                                        redis,
                                        mapper,
                                        properties.getCluster().getShards(),
                                        handler.getEntityType());
                                manager.start();
                                resources.add(manager);

                                @SuppressWarnings("rawtypes")
                                ClusterDispatcher dispatcher = new ClusterDispatcher(redis, mapper,
                                        properties.getCluster().getShards(),
                                        handler.getEntityType());
                                return dispatcher;
                            } else {
                                return builtEngine;
                            }
                        }));

        return new LaminarRegistry(dispatchers, resources);
    }

    @Bean
    public LaminarAspect laminarAspect(LaminarRegistry registry) {
        return new LaminarAspect(registry);
    }

    @Bean
    @ConditionalOnProperty(name = "laminar.saga.enabled", havingValue = "true", matchIfMissing = true)
    public com.nayem.laminar.saga.SagaStateRepository sagaStateRepository(
            LaminarProperties properties,
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            org.springframework.beans.factory.ObjectProvider<ObjectMapper> objectMapperProvider) {

        String stateStore = properties.getSaga().getStateStore();
        return switch (stateStore.toLowerCase()) {
            case "memory" -> new com.nayem.laminar.saga.InMemorySagaStateRepository();
            case "redis" -> {
                StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
                ObjectMapper mapper = objectMapperProvider.getIfAvailable();
                if (redis == null || mapper == null) {
                    throw new IllegalStateException("Redis and ObjectMapper are required for Redis Saga State Store");
                }
                yield new com.nayem.laminar.saga.RedisSagaStateRepository(redis, mapper);
            }
            default -> {
                org.slf4j.LoggerFactory.getLogger(LaminarAutoConfiguration.class)
                        .warn("Unknown saga state store '{}', falling back to memory", stateStore);
                yield new com.nayem.laminar.saga.InMemorySagaStateRepository();
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "laminar.saga.enabled", havingValue = "true", matchIfMissing = true)
    public com.nayem.laminar.saga.SagaMetrics sagaMetrics(
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registryProvider) {
        return new com.nayem.laminar.saga.SagaMetrics(registryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "laminar.saga.enabled", havingValue = "true", matchIfMissing = true)
    public com.nayem.laminar.saga.SagaRecoveryLock sagaRecoveryLock(
            LaminarProperties properties,
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisTemplateProvider) {

        if (properties.getSaga().getRecovery().isDistributedLocking()) {
            StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
            if (redis == null) {
                throw new IllegalStateException("Redis is required for Distributed Saga Recovery Lock");
            }
            return new com.nayem.laminar.saga.RedisSagaRecoveryLock(redis);
        } else {
            return new com.nayem.laminar.saga.NoOpSagaRecoveryLock();
        }
    }

    @Bean
    @ConditionalOnProperty(name = "laminar.saga.enabled", havingValue = "true", matchIfMissing = true)
    public com.nayem.laminar.saga.SagaOrchestrator<?> sagaOrchestrator(
            com.nayem.laminar.saga.SagaStateRepository stateRepository,
            LaminarRegistry registry,
            LaminarProperties properties,
            com.nayem.laminar.saga.SagaMetrics sagaMetrics,
            com.nayem.laminar.saga.SagaRecoveryLock recoveryLock,
            org.springframework.beans.factory.ObjectProvider<ObjectMapper> objectMapperProvider) {

        ObjectMapper mapper = objectMapperProvider.getIfAvailable();
        if (mapper == null) {
            mapper = new ObjectMapper();
        }

        return new com.nayem.laminar.saga.SagaOrchestrator<>(
                stateRepository,
                registry,
                properties.getSaga().getRetry().getBackoff(),
                properties.getSaga().getRetry().getMaxAttempts(),
                properties.getSaga().getRetry().getJitterPercent(),
                properties.getSaga().getRecovery(),
                sagaMetrics,
                recoveryLock,
                mapper,
                properties.getSaga().getTimeout());
    }

    @Bean
    @ConditionalOnProperty(name = "laminar.saga.enabled", havingValue = "true", matchIfMissing = true)
    public SagaAspect sagaAspect(com.nayem.laminar.saga.SagaOrchestrator<?> orchestrator) {
        return new SagaAspect(orchestrator);
    }
}
