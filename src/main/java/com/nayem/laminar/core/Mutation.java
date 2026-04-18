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
     * Returns the unique ID of the entity this mutation affects.
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
    
    /**
     * Returns the priority level of this mutation. Higher values indicate higher priority.
     * <p>
     * Default implementation returns {@code 0}, which means "unspecified/default priority".
     * Override to provide an explicit business priority.
     * </p>
     * <p>
     * Suggested priority convention:
     * </p>
     * <ul>
     *   <li>0: Default/unspecified priority</li>
     *   <li>1-3: Low priority (background tasks, analytics)</li>
     *   <li>4-6: Normal priority (standard user operations)</li>
     *   <li>7-9: High priority (premium users, time-sensitive operations)</li>
     *   <li>10: Critical priority (security operations, system-critical updates)</li>
     * </ul>
     *
     * @return priority level (default: {@code 0})
     */
    default int getPriority() {
        return 0;
    }
}
