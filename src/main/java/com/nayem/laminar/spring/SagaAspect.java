package com.nayem.laminar.spring;

import com.nayem.laminar.saga.LaminarSaga;
import com.nayem.laminar.saga.SagaOrchestrator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aspect that intercepts methods annotated with @DispatchSaga
 * and executes the returned saga through the SagaOrchestrator.
 */
@Aspect
public class SagaAspect {

    private static final Logger log = LoggerFactory.getLogger(SagaAspect.class);

    private final SagaOrchestrator<?> orchestrator;

    public SagaAspect(SagaOrchestrator<?> orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Around("@annotation(com.nayem.laminar.spring.DispatchSaga)")
    public Object dispatchSaga(ProceedingJoinPoint joinPoint) throws Throwable {
        Object result = joinPoint.proceed();

        if (result == null) {
            log.warn("@DispatchSaga method returned null, skipping saga execution");
            return null;
        }

        if (!(result instanceof LaminarSaga<?>)) {
            throw new IllegalStateException(
                    "@DispatchSaga method must return LaminarSaga<?>, got: " + result.getClass().getName());
        }

        @SuppressWarnings("unchecked")
        LaminarSaga<Object> saga = (LaminarSaga<Object>) result;

        log.debug("Dispatching saga: {} ({})", saga.getSagaId(), saga.getDescription());

        @SuppressWarnings("unchecked")
        SagaOrchestrator<Object> typedOrchestrator = (SagaOrchestrator<Object>) orchestrator;

        typedOrchestrator.execute(saga)
                .exceptionally(ex -> {
                    log.error("Saga {} failed: {}", saga.getSagaId(), ex.getMessage());
                    return null;
                });

        return null;
    }
}
