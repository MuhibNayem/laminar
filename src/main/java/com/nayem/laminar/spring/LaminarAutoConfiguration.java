package com.nayem.laminar.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayem.laminar.core.LaminarDispatcher;
import com.nayem.laminar.core.LaminarEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
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
                            // 1. Create the Local Engine (Always needed for processing)
                            LaminarEngine<?> engine = LaminarEngine.builder()
                                    .loader((String k) -> ((LaminarHandler<Object>) handler).load(k))
                                    .saver((Object e) -> ((LaminarHandler<Object>) handler).save(e))
                                    .metrics(registryProvider.getIfAvailable())
                                    .maxWaiters(properties.getMaxWaiters())
                                    .timeout(properties.getTimeout())
                                    .workerEvictionTime(properties.getWorkerEvictionTime())
                                    .maxCachedWorkers(properties.getMaxCachedWorkers())
                                    .shutdownTimeout(properties.getShutdownTimeout())
                                    .shutdownPollingInterval(properties.getShutdownPollingInterval())
                                    .threadNamePrefix(properties.getThreadNamePrefix())
                                    .build();

                            // Track for shutdown
                            resources.add(engine);

                            // 2. Decide: Return Engine (Local) or ClusterDispatcher (Distributed)
                            if (properties.getCluster().isEnabled()) {
                                StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
                                ObjectMapper mapper = objectMapperProvider.getIfAvailable();
                                if (redis == null || mapper == null) {
                                    throw new IllegalStateException(
                                            "Redis and ObjectMapper are required for Laminar Cluster Mode");
                                }

                                // Start the Worker Manager (Consumer)
                                ClusterWorkerManager<?> manager = new ClusterWorkerManager(
                                        engine,
                                        redis,
                                        mapper,
                                        properties.getCluster().getShards(),
                                        handler.getEntityType());
                                manager.start();
                                resources.add(manager);

                                // Return the Gateway that writes to Redis
                                return new ClusterDispatcher(redis, mapper, properties.getCluster().getShards(),
                                        handler.getEntityType());
                            } else {
                                return engine;
                            }
                        }));

        return new LaminarRegistry(dispatchers, resources);
    }

    @Bean
    public LaminarAspect laminarAspect(LaminarRegistry registry) {
        return new LaminarAspect(registry);
    }
}
