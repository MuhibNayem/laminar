package com.nayem.laminar.saga;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A fluent builder for creating {@link LaminarSaga} instances without defining
 * a separate class.
 * <p>
 * This allows inline saga definition for simple workflows.
 * </p>
 *
 * <h3>Example Usage:</h3>
 * 
 * <pre>{@code
 * LaminarSaga<Account> transferSaga = SagaBuilder.<Account>newSaga("transfer-123", Account.class)
 *         .description("Transfer $100 from A to B")
 *         .step(FunctionalSagaStep.<Account>builder()
 *                 .stepId("debit")
 *                 .entityKey(fromAccountId)
 *                 .execute(acc -> {
 *                     acc.withdraw(100);
 *                     return acc;
 *                 })
 *                 .compensate((acc, prev) -> acc.deposit(100))
 *                 .build())
 *         .step(FunctionalSagaStep.<Account>builder()
 *                 .stepId("credit")
 *                 .entityKey(toAccountId)
 *                 .execute(acc -> {
 *                     acc.deposit(100);
 *                     return acc;
 *                 })
 *                 .compensate((acc, prev) -> acc.withdraw(100))
 *                 .build())
 *         .onSuccess(() -> log.info("Transfer completed!"))
 *         .onFailure(e -> log.error("Transfer failed", e))
 *         .build();
 * }</pre>
 *
 * @param <T> The entity type
 */
public class SagaBuilder<T> {

    private final String sagaId;
    private final Class<T> entityType;
    private final List<SagaStep<T>> steps = new ArrayList<>();
    private String description;
    private Runnable onSuccessCallback;
    private Consumer<Throwable> onFailureCallback;

    private SagaBuilder(String sagaId, Class<T> entityType) {
        this.sagaId = sagaId;
        this.entityType = entityType;
    }

    /**
     * Creates a new SagaBuilder.
     *
     * @param sagaId     Unique identifier for this saga instance
     * @param entityType The type of entity this saga operates on
     * @param <T>        The entity type
     * @return A new builder instance
     */
    public static <T> SagaBuilder<T> newSaga(String sagaId, Class<T> entityType) {
        return new SagaBuilder<>(sagaId, entityType);
    }

    /**
     * Adds a description for logging/monitoring.
     */
    public SagaBuilder<T> description(String description) {
        this.description = description;
        return this;
    }

    /**
     * Adds a step to the saga.
     * Steps are executed in the order they are added.
     */
    public SagaBuilder<T> step(SagaStep<T> step) {
        this.steps.add(step);
        return this;
    }

    /**
     * Registers a callback to be invoked when all steps complete successfully.
     */
    public SagaBuilder<T> onSuccess(Runnable callback) {
        this.onSuccessCallback = callback;
        return this;
    }

    /**
     * Registers a callback to be invoked when the saga fails.
     */
    public SagaBuilder<T> onFailure(Consumer<Throwable> callback) {
        this.onFailureCallback = callback;
        return this;
    }

    /**
     * Builds the LaminarSaga instance.
     *
     * @throws IllegalStateException if no steps are defined
     */
    public LaminarSaga<T> build() {
        if (steps.isEmpty()) {
            throw new IllegalStateException("A saga must have at least one step");
        }

        return new LaminarSaga<>() {
            @Override
            public String getSagaId() {
                return sagaId;
            }

            @Override
            public Class<T> getEntityType() {
                return entityType;
            }

            @Override
            public List<SagaStep<T>> getSteps() {
                return List.copyOf(steps);
            }

            @Override
            public void onSuccess() {
                if (onSuccessCallback != null) {
                    onSuccessCallback.run();
                }
            }

            @Override
            public void onFailure(Throwable cause) {
                if (onFailureCallback != null) {
                    onFailureCallback.accept(cause);
                }
            }

            @Override
            public String getDescription() {
                return description != null ? description : "Saga-" + sagaId;
            }
        };
    }
}
