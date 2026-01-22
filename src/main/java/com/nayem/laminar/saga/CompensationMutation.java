package com.nayem.laminar.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayem.laminar.core.Mutation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A mutation that executes the compensation/rollback logic for a SagaStep.
 * <p>
 * Production-hardened with:
 * - Idempotency check (skips already compensated steps)
 * - Timeout protection
 * - Non-blocking execution (retries handled by orchestrator)
 * - Previous state restoration
 * </p>
 */
public class CompensationMutation<T> implements Mutation<T> {

    private static final Logger log = LoggerFactory.getLogger(CompensationMutation.class);
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final SagaStep<T> step;
    private final String sagaId;
    private final SagaStateRepository stateRepository;
    private final long timeoutMs;
    private final ObjectMapper objectMapper;
    private final Class<T> entityClass;

    public CompensationMutation(SagaStep<T> step, String sagaId, SagaStateRepository stateRepository, ObjectMapper objectMapper, Class<T> entityClass) {
        this(step, sagaId, stateRepository, DEFAULT_TIMEOUT_MS, objectMapper, entityClass);
    }

    public CompensationMutation(SagaStep<T> step, String sagaId, SagaStateRepository stateRepository, long timeoutMs, ObjectMapper objectMapper, Class<T> entityClass) {
        this.step = step;
        this.sagaId = sagaId;
        this.stateRepository = stateRepository;
        this.timeoutMs = timeoutMs;
        this.objectMapper = objectMapper;
        this.entityClass = entityClass;
    }

    @Override
    public String getEntityKey() {
        return step.getEntityKey();
    }

    @Override
    public Mutation<T> coalesce(Mutation<T> other) {
        return new ChainedSagaMutation<>(this, other);
    }

    @Override
    public void apply(T entity) {
        String stepId = step.getStepId();

        var stateOpt = stateRepository.findById(sagaId);
        if (stateOpt.isEmpty()) {
            log.warn("Saga {} state not found, skipping compensation for step {}", sagaId, stepId);
            return;
        }

        SagaState state = stateOpt.get();

        if (state.isCompensated(stepId)) {
            log.debug("Saga {} step {} already compensated, skipping", sagaId, stepId);
            return;
        }

        try {
            String previousStateJson = state.stepResults().get(stepId);
            T previousState = null;
            if (previousStateJson != null && !previousStateJson.equals("UNKNOWN")) {
                 try {
                     previousState = objectMapper.readValue(previousStateJson, entityClass);
                 } catch (Exception e) {
                     log.warn("Failed to deserialize previous state for saga {} step {}. Compensation proceeds with null previous state.", sagaId, stepId, e);
                 }
            }

            final T finalPreviousState = previousState;

            CompletableFuture<Void> compensationFuture = CompletableFuture.runAsync(() -> {
                step.compensate(entity, finalPreviousState);
            });

            compensationFuture.get(timeoutMs, TimeUnit.MILLISECONDS);

            SagaState updated = stateRepository.findById(sagaId)
                    .orElse(state)
                    .withCompensatedStep(stepId);
            stateRepository.save(updated);

            log.info("Saga {} step {} compensated successfully", sagaId, stepId);

        } catch (TimeoutException e) {
            log.error("Saga {} step {} compensation timed out after {}ms", sagaId, stepId, timeoutMs);
            recordFailure(stepId, "Timeout after " + timeoutMs + "ms");
            throw new CompensationException("Compensation timed out", e);

        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Saga {} step {} compensation failed: {}", sagaId, stepId, cause.getMessage());
            recordFailure(stepId, cause.getMessage());
            throw new CompensationException("Compensation failed: " + cause.getMessage(), cause);
        }
    }

    private void recordFailure(String stepId, String reason) {
        try {
            var stateOpt = stateRepository.findById(sagaId);
            if (stateOpt.isPresent()) {
                SagaState updated = stateOpt.get().withFailedCompensation(stepId, reason);
                stateRepository.save(updated);
            }
        } catch (Exception e) {
            log.error("Failed to record compensation failure: {}", e.getMessage());
        }
    }

    public static class CompensationException extends RuntimeException {
        public CompensationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}