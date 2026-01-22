package com.nayem.laminar.saga;

import java.util.List;

/**
 * Defines a complete Saga transaction with ordered steps.
 * <p>
 * A Saga is a sequence of local transactions where each step has a
 * compensating action. If any step fails, previously completed steps
 * are compensated in reverse order.
 * </p>
 *
 * @param <T> The entity type being mutated
 */
public interface LaminarSaga<T> {

    /**
     * Unique identifier for this saga instance.
     * Used for state persistence, idempotency, and crash recovery.
     */
    String getSagaId();

    /**
     * The type of entity this saga operates on.
     */
    Class<T> getEntityType();

    /**
     * Ordered list of steps to execute.
     * Steps are executed in order; on failure, compensation runs in reverse.
     */
    List<SagaStep<T>> getSteps();

    /**
     * Called when all steps complete successfully.
     */
    default void onSuccess() {
    }

    /**
     * Called when the saga fails after all compensations complete.
     *
     * @param cause The exception that caused the failure
     */
    default void onFailure(Throwable cause) {
    }

    /**
     * Optional: Provides a description for logging/monitoring.
     */
    default String getDescription() {
        return getClass().getSimpleName();
    }
}
