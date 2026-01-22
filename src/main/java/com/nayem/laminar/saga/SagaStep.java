package com.nayem.laminar.saga;

/**
 * Represents an individual step within a Saga transaction.
 * <p>
 * Each step has:
 * - A forward action (execute)
 * - A compensating action (compensate) for rollback
 * </p>
 *
 * @param <T> The entity type being mutated
 */
public interface SagaStep<T> {

    /**
     * Unique identifier for this step.
     * Used for idempotency and state tracking.
     */
    String getStepId();

    /**
     * The entity key this step affects.
     * Used for lock ordering and routing.
     */
    String getEntityKey();

    /**
     * Execute the forward action on the entity.
     *
     * @param entity The current state of the entity
     * @return The modified entity (also serves as context for compensation)
     */
    T execute(T entity);

    /**
     * Compensate/rollback this step if a later step fails.
     *
     * @param entity        The current state of the entity
     * @param previousState The state before execute() was called (for reversal)
     */
    void compensate(T entity, T previousState);

    /**
     * Whether this step is idempotent (safe to retry).
     * Idempotent steps can be retried without side effects if already executed.
     *
     * @return true if idempotent (default), false otherwise
     */
    default boolean isIdempotent() {
        return true;
    }

    /**
     * Maximum retry attempts for this step before failing the saga.
     *
     * @return max attempts (default: 3)
     */
    default int maxRetries() {
        return 3;
    }
}
