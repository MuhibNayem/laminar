package com.nayem.laminar.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>
 * <strong>Safety Features:</strong>
 * <ul>
 * <li>Batch size limiting to prevent memory exhaustion</li>
 * <li>Cyclic coalescing detection to prevent infinite loops</li>
 * <li>Thread-safe mutation accumulation</li>
 * </ul>
 * </p>
 *
 * @param <T> The entity type
 */
public class CoalescingBatch<T> {
    private static final Logger log = LoggerFactory.getLogger(CoalescingBatch.class);

    private volatile Mutation<T> accumulatedMutation;
    private final List<CompletableFuture<Void>> waiters;
    private final int maxBatchSize;
    private final int maxCoalesceIterations;
    private int coalescenceCount = 0;

    public CoalescingBatch(Mutation<T> initialMutation) {
        this(initialMutation, 10000, 1000);
    }

    public CoalescingBatch(Mutation<T> initialMutation, int maxBatchSize, int maxCoalesceIterations) {
        this.accumulatedMutation = initialMutation;
        this.waiters = new ArrayList<>(1);
        this.maxBatchSize = maxBatchSize;
        this.maxCoalesceIterations = maxCoalesceIterations;
    }

    /**
     * Merges a new mutation into the current accumulated state.
     * 
     * @throws IllegalStateException if batch size limit exceeded
     * @throws IllegalStateException if cyclic coalescing detected
     */
    public void add(Mutation<T> newMutation) {
        if (!accumulatedMutation.getEntityKey().equals(newMutation.getEntityKey())) {
            throw new IllegalArgumentException("Cannot merge mutations for different entities");
        }

        if (waiters.size() > maxBatchSize) {
            throw new IllegalStateException(
                    String.format("Batch size limit exceeded (%d). This prevents memory exhaustion from " +
                            "unbounded coalescing. Consider increasing laminar.max-batch-size or reviewing " +
                            "mutation coalesce() logic.", maxBatchSize));
        }

        Mutation<T> previousMutation = this.accumulatedMutation;
        Mutation<T> coalescedMutation = previousMutation.coalesce(newMutation);

        if (coalescedMutation == previousMutation) {
            coalescenceCount++;
            if (coalescenceCount >= maxCoalesceIterations) {
                log.error("Cyclic coalescing detected for entity key '{}' after {} consecutive " +
                        "same-instance returns. Mutation class: {}. This usually indicates coalesce() " +
                        "is returning 'this' indefinitely. Review your coalesce() implementation.",
                        accumulatedMutation.getEntityKey(),
                        coalescenceCount,
                        accumulatedMutation.getClass().getName());

                throw new IllegalStateException(
                        String.format("Cyclic coalescing detected after %d consecutive same-instance returns " +
                                "for entity '%s'. Mutation.coalesce() appears to be returning 'this' indefinitely. " +
                                "Check your coalesce() implementation.",
                                maxCoalesceIterations,
                                accumulatedMutation.getEntityKey()));
            }
        } else {
            coalescenceCount = 0;
        }

        this.accumulatedMutation = coalescedMutation;
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

    /**
     * Returns the number of coalescing operations performed on this batch.
     * Useful for metrics and debugging.
     */
    public int getCoalescenceCount() {
        return coalescenceCount;
    }

    /**
     * Returns the current batch size (number of waiting futures).
     */
    public int getBatchSize() {
        return waiters.size();
    }
}
