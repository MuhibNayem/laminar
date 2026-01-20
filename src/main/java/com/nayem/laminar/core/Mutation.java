package com.nayem.laminar.core;

/**
 * Represents an atomic unit of change for a specific entity.
 * <p>
 * This interface is the heart of the "Laminar" engine. It allows operations to
 * be
 * merged (coalesced) before they are executed, significantly reducing database
 * pressure.
 * </p>
 *
 * @param <T> The type of the entity being mutated (e.g., UserProfile,
 *            ChannelStats).
 */
public interface Mutation<T> {

    /**
     * returns the unique ID of the entity this mutation affects.
     * All mutations with the same Key will be routed to the same worker.
     */
    String getEntityKey();

    /**
     * Merges this mutation with a subsequent mutation to produce a single, combined
     * mutation.
     * <p>
     * Logic:
     * Current State (This) + New State (Other) = Combined State.
     * </p>
     *
     * @param other The next mutation that arrived while this one was pending.
     * @return A new Mutation acting as the combined result, or 'this' if merging is
     *         simple state replacement.
     */
    Mutation<T> coalesce(Mutation<T> other);

    /**
     * Applies the logic to the entity appropriately.
     * In a real DB scenario, this might return a Query object or a partial update
     * map
     * rather than modifying a POJO in memory.
     *
     * @param entity The current state of the entity (could be null if it's a create
     *               op).
     */
    void apply(T entity);
}
