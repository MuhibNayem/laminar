package com.nayem.laminar.saga;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for persisting Saga state.
 * <p>
 * Implementations can store state in:
 * - In-Memory (for testing/development)
 * - Redis (recommended for production clustering)
 * - JDBC (for traditional databases)
 * </p>
 */
public interface SagaStateRepository {

    /**
     * Save or update saga state.
     *
     * @param state The state to persist
     */
    void save(SagaState state);

    /**
     * Find saga state by ID.
     *
     * @param sagaId The saga identifier
     * @return Optional containing the state if found
     */
    Optional<SagaState> findById(String sagaId);

    /**
     * Find all sagas that are PENDING, RUNNING, or COMPENSATING.
     * Used for crash recovery on application startup.
     *
     * @return List of incomplete sagas
     */
    List<SagaState> findIncomplete();

    /**
     * Delete saga state after successful completion or final failure.
     *
     * @param sagaId The saga identifier
     */
    void delete(String sagaId);

    /**
     * Check if a saga with this ID already exists.
     * Used for idempotency checks.
     *
     * @param sagaId The saga identifier
     * @return true if exists
     */
    default boolean exists(String sagaId) {
        return findById(sagaId).isPresent();
    }
}
