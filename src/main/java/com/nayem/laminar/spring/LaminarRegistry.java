package com.nayem.laminar.spring;

import com.nayem.laminar.core.LaminarEngine;

import java.util.Map;

public class LaminarRegistry {
    private final Map<Class<?>, LaminarEngine<?>> engines;

    public LaminarRegistry(Map<Class<?>, LaminarEngine<?>> engines) {
        this.engines = engines;
    }

    @SuppressWarnings("unchecked")
    public <T> LaminarEngine<T> getEngine(Class<T> type) {
        return (LaminarEngine<T>) engines.get(type);
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        engines.values().forEach(LaminarEngine::shutdown);
    }
}
