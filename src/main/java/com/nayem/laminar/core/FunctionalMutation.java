package com.nayem.laminar.core;

import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * A generic wrapper for {@link Mutation} that allows defining mutations using
 * lambdas.
 * <p>
 * This reduces boilerplate by avoiding the need to create a separate class for
 * every simple mutation.
 * </p>
 *
 * @param <T> The entity type
 */
public class FunctionalMutation<T> implements Mutation<T> {

    private final String entityKey;
    private final Consumer<T> applier;
    private final BiFunction<Mutation<T>, Mutation<T>, Mutation<T>> coalescer;

    private FunctionalMutation(String entityKey, Consumer<T> applier,
            BiFunction<Mutation<T>, Mutation<T>, Mutation<T>> coalescer) {
        this.entityKey = entityKey;
        this.applier = applier;
        this.coalescer = coalescer;
    }

    /**
     * Creates a simple mutation that does NOT coalesce.
     *
     * @param entityKey The entity ID
     * @param applier   The logic to apply to the entity
     */
    public static <T> FunctionalMutation<T> of(String entityKey, Consumer<T> applier) {
        return new FunctionalMutation<>(entityKey, applier, (current, next) -> next);
    }

    /**
     * Creates a coalescing mutation.
     *
     * @param entityKey The entity ID
     * @param applier   The logic to apply to the entity
     * @param coalescer The logic to merge with another mutation
     */
    public static <T> FunctionalMutation<T> of(String entityKey, Consumer<T> applier,
            BiFunction<Mutation<T>, Mutation<T>, Mutation<T>> coalescer) {
        return new FunctionalMutation<>(entityKey, applier, coalescer);
    }

    @Override
    public String getEntityKey() {
        return entityKey;
    }

    @Override
    public Mutation<T> coalesce(Mutation<T> other) {
        if (coalescer == null) {
            return other;
        }
        return coalescer.apply(this, other);
    }

    @Override
    public void apply(T entity) {
        applier.accept(entity);
    }
}
