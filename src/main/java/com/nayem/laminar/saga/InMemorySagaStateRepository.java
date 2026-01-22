package com.nayem.laminar.saga;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of SagaStateRepository.
 * <p>
 * Suitable for:
 * - Development and testing
 * - Single-instance deployments
 * </p>
 * <p>
 * Note: State is lost on application restart. For production clustering,
 * use Redis or JDBC implementations.
 * </p>
 */
public class InMemorySagaStateRepository implements SagaStateRepository {

    private final Map<String, SagaState> store = new ConcurrentHashMap<>();

    @Override
    public void save(SagaState state) {
        store.put(state.sagaId(), state);
    }

    @Override
    public Optional<SagaState> findById(String sagaId) {
        return Optional.ofNullable(store.get(sagaId));
    }

    @Override
    public List<SagaState> findIncomplete() {
        return store.values().stream()
                .filter(state -> state.status() == SagaStatus.PENDING
                        || state.status() == SagaStatus.RUNNING
                        || state.status() == SagaStatus.COMPENSATING)
                .toList();
    }

    @Override
    public void delete(String sagaId) {
        store.remove(sagaId);
    }

    /**
     * Clears all stored state. Useful for testing.
     */
    public void clear() {
        store.clear();
    }

    /**
     * Returns the current number of stored sagas.
     */
    public int size() {
        return store.size();
    }
}
