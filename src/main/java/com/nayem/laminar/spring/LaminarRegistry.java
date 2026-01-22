package com.nayem.laminar.spring;

import com.nayem.laminar.core.LaminarDispatcher;

import java.util.Map;

public class LaminarRegistry implements org.springframework.beans.factory.DisposableBean {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LaminarRegistry.class);

    private final Map<Class<?>, LaminarDispatcher<?>> dispatchers;
    private final java.util.List<AutoCloseable> resources;

    public LaminarRegistry(Map<Class<?>, LaminarDispatcher<?>> dispatchers, java.util.List<AutoCloseable> resources) {
        this.dispatchers = dispatchers;
        this.resources = resources;
    }

    @SuppressWarnings("unchecked")
    public <T> LaminarDispatcher<T> getDispatcher(Class<T> type) {
        return (LaminarDispatcher<T>) dispatchers.get(type);
    }

    public java.util.List<AutoCloseable> getResources() {
        return resources;
    }

    @Override
    public void destroy() {
        log.info("LaminarRegistry shutting down, closing {} resources...", resources.size());
        for (AutoCloseable resource : resources) {
            try {
                if (resource instanceof com.nayem.laminar.spring.ClusterWorkerManager) {
                    ((com.nayem.laminar.spring.ClusterWorkerManager<?>) resource).shutdown();
                } else if (resource instanceof com.nayem.laminar.core.LaminarEngine) {
                    ((com.nayem.laminar.core.LaminarEngine<?>) resource).shutdown();
                } else {
                    resource.close();
                }
            } catch (Exception e) {
                log.warn("Failed to close Laminar resource: {}", e.getMessage());
            }
        }
    }
}
