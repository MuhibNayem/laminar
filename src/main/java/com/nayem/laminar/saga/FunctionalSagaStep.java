package com.nayem.laminar.saga;

import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/**
 * A functional adapter for {@link SagaStep} that allows defining saga steps
 * using lambdas.
 * <p>
 * This eliminates the need to create a separate class for every saga step,
 * reducing boilerplate
 * significantly for simple operations.
 * </p>
 *
 * <h3>Example Usage:</h3>
 * 
 * <pre>{@code
 * SagaStep<Account> debitStep = FunctionalSagaStep.<Account>builder()
 *         .stepId("debit-" + fromAccountId)
 *         .entityKey(fromAccountId)
 *         .execute(account -> {
 *             account.withdraw(amount);
 *             return account;
 *         })
 *         .compensate((account, previousState) -> {
 *             account.deposit(amount); // Reverse the debit
 *         })
 *         .build();
 * }</pre>
 *
 * @param <T> The entity type
 */
public class FunctionalSagaStep<T> implements SagaStep<T> {

    private final String stepId;
    private final String entityKey;
    private final UnaryOperator<T> executor;
    private final BiConsumer<T, T> compensator;
    private final boolean idempotent;
    private final int maxRetries;

    private FunctionalSagaStep(Builder<T> builder) {
        this.stepId = builder.stepId;
        this.entityKey = builder.entityKey;
        this.executor = builder.executor;
        this.compensator = builder.compensator;
        this.idempotent = builder.idempotent;
        this.maxRetries = builder.maxRetries;
    }

    /**
     * Creates a new builder for FunctionalSagaStep.
     *
     * @param <T> The entity type
     * @return A new builder instance
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    @Override
    public String getStepId() {
        return stepId;
    }

    @Override
    public String getEntityKey() {
        return entityKey;
    }

    @Override
    public T execute(T entity) {
        return executor.apply(entity);
    }

    @Override
    public void compensate(T entity, T previousState) {
        if (compensator != null) {
            compensator.accept(entity, previousState);
        }
    }

    @Override
    public boolean isIdempotent() {
        return idempotent;
    }

    @Override
    public int maxRetries() {
        return maxRetries;
    }

    /**
     * Builder for creating {@link FunctionalSagaStep} instances.
     *
     * @param <T> The entity type
     */
    public static class Builder<T> {
        private String stepId;
        private String entityKey;
        private UnaryOperator<T> executor;
        private BiConsumer<T, T> compensator;
        private boolean idempotent = true;
        private int maxRetries = 3;

        /**
         * Sets the unique step ID.
         */
        public Builder<T> stepId(String stepId) {
            this.stepId = stepId;
            return this;
        }

        /**
         * Sets the entity key this step affects.
         */
        public Builder<T> entityKey(String entityKey) {
            this.entityKey = entityKey;
            return this;
        }

        /**
         * Sets the forward execution logic.
         *
         * @param executor A function that takes the entity and returns the modified
         *                 entity
         */
        public Builder<T> execute(UnaryOperator<T> executor) {
            this.executor = executor;
            return this;
        }

        /**
         * Sets the compensation logic for rollback.
         *
         * @param compensator A consumer that receives (currentEntity, previousState)
         */
        public Builder<T> compensate(BiConsumer<T, T> compensator) {
            this.compensator = compensator;
            return this;
        }

        /**
         * Sets whether this step is idempotent. Default: true
         */
        public Builder<T> idempotent(boolean idempotent) {
            this.idempotent = idempotent;
            return this;
        }

        /**
         * Sets the maximum retry attempts. Default: 3
         */
        public Builder<T> maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Builds the FunctionalSagaStep.
         *
         * @throws IllegalStateException if required fields are missing
         */
        public FunctionalSagaStep<T> build() {
            if (stepId == null || stepId.isBlank()) {
                throw new IllegalStateException("stepId is required");
            }
            if (entityKey == null || entityKey.isBlank()) {
                throw new IllegalStateException("entityKey is required");
            }
            if (executor == null) {
                throw new IllegalStateException("execute function is required");
            }
            return new FunctionalSagaStep<>(this);
        }
    }
}
