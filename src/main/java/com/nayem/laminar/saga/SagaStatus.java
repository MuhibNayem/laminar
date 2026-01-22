package com.nayem.laminar.saga;

/**
 * Represents the execution status of a Saga.
 */
public enum SagaStatus {

    /**
     * Saga created but not yet started.
     */
    PENDING,

    /**
     * Saga is currently executing forward steps.
     */
    RUNNING,

    /**
     * Saga failed and is currently executing compensations.
     */
    COMPENSATING,

    /**
     * Saga completed successfully (all steps executed).
     */
    COMPLETED,

    /**
     * Saga failed (after compensations completed or compensation failed).
     */
    FAILED
}
