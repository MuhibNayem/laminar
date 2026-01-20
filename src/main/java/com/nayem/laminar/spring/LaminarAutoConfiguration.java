package com.nayem.laminar.spring;

import com.nayem.laminar.core.LaminarEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@EnableAspectJAutoProxy
@org.springframework.boot.context.properties.EnableConfigurationProperties(LaminarProperties.class)
public class LaminarAutoConfiguration {

    @Bean
    @SuppressWarnings("unchecked")
    public LaminarRegistry laminarRegistry(List<LaminarHandler<?>> handlers,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registryProvider,
            LaminarProperties properties) {
        Map<Class<?>, LaminarEngine<?>> engines = handlers.stream()
                .collect(Collectors.toMap(
                        LaminarHandler::getEntityType,
                        handler -> LaminarEngine.builder()
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
                                .build()));

        return new LaminarRegistry(engines);
    }

    @Bean
    public LaminarAspect laminarAspect(LaminarRegistry registry) {
        return new LaminarAspect(registry);
    }
}
