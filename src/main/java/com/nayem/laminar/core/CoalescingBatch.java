package com.nayem.laminar.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a "Smart Batch" of operations pending structure for a single
 * entity.
 * <p>
 * Instead of a queue of 100 items, we hold 1 items (the coalesced state)
 * and 100 listeners waiting for that state to committed.
 * </p>
 *
 * @param <T> The entity type
 */
public class CoalescingBatch<T> {
    private volatile Mutation<T> accumulatedMutation;
    private final List<CompletableFuture<Void>> waiters;

    public CoalescingBatch(Mutation<T> initialMutation) {
        this.accumulatedMutation = initialMutation;
        this.waiters = new ArrayList<>();
    }

    /**
     * Merges a new mutation into the current accumulated state.
     */
    public void add(Mutation<T> newMutation) {
        if (!accumulatedMutation.getEntityKey().equals(newMutation.getEntityKey())) {
            throw new IllegalArgumentException("Cannot merge mutations for different entities");
        }
        this.accumulatedMutation = this.accumulatedMutation.coalesce(newMutation);
    }

    /**
     * Registers a future that should be completed when this batch is flushed.
     */
    public void addWaiter(CompletableFuture<Void> future) {
        this.waiters.add(future);
    }

    public Mutation<T> getAccumulatedMutation() {
        return accumulatedMutation;
    }

    public List<CompletableFuture<Void>> getWaiters() {
        return waiters;
    }
}
