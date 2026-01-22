package com.nayem.laminar.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayem.laminar.core.Mutation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Wraps a SagaStep execution into a Laminar Mutation.
 * <p>
 * This allows Saga steps to be processed by the LaminarEngine, enabling
 * request coalescing (batching) for high throughput.
 * </p>
 */
public class SagaStepMutation<T> implements Mutation<T> {

    private static final Logger log = LoggerFactory.getLogger(SagaStepMutation.class);

    private final SagaStep<T> step;
    private final String sagaId;
    private final SagaStateRepository stateRepository;
    private final Consumer<T> onSuccess;
    private final Consumer<Throwable> onFailure;
    private final ObjectMapper objectMapper;

    public SagaStepMutation(SagaStep<T> step, String sagaId, SagaStateRepository stateRepository,
            Consumer<T> onSuccess, Consumer<Throwable> onFailure, ObjectMapper objectMapper) {
        this.step = step;
        this.sagaId = sagaId;
        this.stateRepository = stateRepository;
        this.onSuccess = onSuccess;
        this.onFailure = onFailure;
        this.objectMapper = objectMapper;
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
        try {
            var stateOpt = stateRepository.findById(sagaId);
            if (stateOpt.isPresent()) {
                SagaState state = stateOpt.get();
                if (state.stepResults().containsKey(step.getStepId())) {
                    log.info("Saga {} step {} already executed, skipping (idempotent)", sagaId, step.getStepId());
                    if (onSuccess != null) {
                        onSuccess.accept(entity);
                    }
                    return;
                }
            }

            String previousStateJson = null;
            try {
                previousStateJson = objectMapper.writeValueAsString(entity);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize previous state for saga {} step {}. Compensation may be limited.", sagaId, step.getStepId(), e);
            }

            T result = step.execute(entity);

            String finalPreviousStateJson = previousStateJson;
            stateOpt.ifPresent(state -> {
                SagaState updated = state.withStepResult(step.getStepId(), finalPreviousStateJson != null ? finalPreviousStateJson : "UNKNOWN");
                stateRepository.save(updated);
            });

            if (onSuccess != null) {
                onSuccess.accept(result);
            }
        } catch (Throwable t) {
            log.warn("Saga step {} failed: {}", step.getStepId(), t.getMessage());
            if (onFailure != null) {
                onFailure.accept(t);
            }
            throw t;
        }
    }
}