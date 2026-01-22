package com.nayem.laminar.saga;

import com.nayem.laminar.core.Mutation;

/**
 * A composite mutation that executes two mutations sequentially.
 * Used for coalescing multiple Saga steps targeting the same entity.
 */
public class ChainedSagaMutation<T> implements Mutation<T> {

    private final Mutation<T> first;
    private final Mutation<T> second;

    public ChainedSagaMutation(Mutation<T> first, Mutation<T> second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public String getEntityKey() {
        return first.getEntityKey();
    }

    @Override
    public Mutation<T> coalesce(Mutation<T> other) {
        return new ChainedSagaMutation<>(this, other);
    }

    @Override
    public void apply(T entity) {
        first.apply(entity);
        second.apply(entity);
    }
}
