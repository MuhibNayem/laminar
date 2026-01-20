package com.nayem.laminar.spring;

import com.nayem.laminar.core.Mutation;
import com.nayem.laminar.core.LaminarEngine;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

@Aspect
public class LaminarAspect {

    private final LaminarRegistry registry;

    public LaminarAspect(LaminarRegistry registry) {
        this.registry = registry;
    }

    @Around("@annotation(com.nayem.laminar.spring.Dispatch)")
    @SuppressWarnings("unchecked")
    public Object handleDispatch(ProceedingJoinPoint joinPoint) throws Throwable {
        Object result = joinPoint.proceed();

        if (!(result instanceof Mutation<?> mutation)) {
            throw new IllegalStateException("@Dispatch method must return a Mutation object");
        }

        Class<?> entityType = resolveEntityType(mutation);

        if (entityType == null) {
            throw new IllegalStateException(
                    "Could not determine Entity Type for Mutation: " + mutation.getClass().getName());
        }

        LaminarEngine<Object> engine = (LaminarEngine<Object>) registry.getEngine(entityType);

        if (engine == null) {
            throw new IllegalStateException("No LaminarHandler found for entity type: " + entityType.getName());
        }

        engine.dispatch((Mutation<Object>) mutation);

        return result;
    }

    private final java.util.Map<Class<?>, Class<?>> typeCache = new java.util.concurrent.ConcurrentHashMap<>();

    private Class<?> resolveEntityType(Mutation<?> mutation) {
        return typeCache.computeIfAbsent(mutation.getClass(), type -> {
            for (Type genericInterface : type.getGenericInterfaces()) {
                if (genericInterface instanceof ParameterizedType pt) {
                  if (pt.getRawType().equals(Mutation.class)) {
                        return (Class<?>) pt.getActualTypeArguments()[0];
                    }
                }
            }
            return null;
        });
    }
}
