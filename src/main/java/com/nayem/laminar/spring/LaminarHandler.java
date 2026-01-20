package com.nayem.laminar.spring;

/**
 * Interface that Spring Beans should implement to define how to handle
 * persistence
 * for a specific entity type within the Laminar engine.
 *
 * @param <T> The entity type.
 */
public interface LaminarHandler<T> {

    /**
     * Load the entity by its key.
     */
    T load(String key);

    /**
     * Save the entity state.
     */
    void save(T entity);

    /**
     * The Class of the entity this handler manages.
     * Needed for resolving the correct Engine at runtime.
     */
    Class<T> getEntityType();
}
