package com.nayem.laminar.worker;

import com.nayem.laminar.core.CoalescingBatch;
import com.nayem.laminar.core.Mutation;

import com.nayem.laminar.dlq.DeadLetterQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the "Actor" logic for a single Entity Key.
 * <p>
 * It manages the lifecycle of the Virtual Thread:
 * - Spawns when work arrives.
 * </p>
 */
public class EntityWorker<T> {
    private static final Logger log = LoggerFactory.getLogger(EntityWorker.class);

    private final String key;
    private final Consumer<CoalescingBatch<T>> processor;
    private final DeadLetterQueue<T> dlq; // Optional

    private final ReentrantLock lock = new ReentrantLock();
    private CoalescingBatch<T> pendingBatch;
    private volatile boolean isRunning;

    public boolean isRunning() {
        return isRunning;
    }

    private final int maxWaiters;
    private final int maxBatchSize;
    private final int maxCoalesceIterations;
    private final java.util.concurrent.ExecutorService executor;

    public EntityWorker(String key, Consumer<CoalescingBatch<T>> processor, int maxWaiters,
            java.util.concurrent.ExecutorService executor) {
        this(key, processor, maxWaiters, 10000, 1000, executor, null);
    }

    public EntityWorker(String key, Consumer<CoalescingBatch<T>> processor, int maxWaiters,
            int maxBatchSize, int maxCoalesceIterations, java.util.concurrent.ExecutorService executor,
            DeadLetterQueue<T> dlq) {
        this.key = key;
        this.processor = processor;
        this.maxWaiters = maxWaiters;
        this.maxBatchSize = maxBatchSize;
        this.maxCoalesceIterations = maxCoalesceIterations;
        this.executor = executor;
        this.dlq = dlq;
    }

    /**
     * Submits a mutation to this worker.
     * Thread-safe, non-blocking.
     */
    public CompletableFuture<Void> submit(Mutation<T> mutation) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        lock.lock();
        try {
            int currentWaiters = (pendingBatch != null) ? pendingBatch.getWaiters().size() : 0;
            if (currentWaiters >= maxWaiters) {
                future.completeExceptionally(new java.util.concurrent.RejectedExecutionException(
                        "Laminar Worker Backpressure: Too many pending requests for key: " + key));
                return future;
            }

            if (pendingBatch == null) {
                pendingBatch = new CoalescingBatch<>(mutation, maxBatchSize, maxCoalesceIterations);
            } else {
                try {
                    pendingBatch.add(mutation);
                } catch (IllegalStateException e) {
                    future.completeExceptionally(e);
                    return future;
                }
            }
            pendingBatch.addWaiter(future);

            if (!isRunning) {
                isRunning = true;
                executor.execute(this::runLoop);
            }
        } finally {
            lock.unlock();
        }

        return future;
    }

    private void runLoop() {
        while (true) {
            CoalescingBatch<T> batchToProcess = null;

            lock.lock();
            try {
                if (pendingBatch == null) {
                    isRunning = false;
                    return;
                }
                batchToProcess = pendingBatch;
                pendingBatch = null;
            } finally {
                lock.unlock();
            }

            try {
                processor.accept(batchToProcess);
                batchToProcess.getWaiters().forEach(f -> f.complete(null));
            } catch (Exception e) {
                if (dlq != null) {
                    try {
                        Mutation<T> m = batchToProcess.getAccumulatedMutation();
                        dlq.send(new DeadLetterQueue.DlqEntry<>(
                                UUID.randomUUID().toString(),
                                m,
                                key,
                                e.getMessage() != null ? e.getMessage() : "Unknown error",
                                e.getClass().getName(),
                                0,
                                Instant.now(),
                                null));
                    } catch (Exception dlqEx) {
                        log.error("Failed to send mutation to DLQ for key: " + key, dlqEx);
                    }
                }
                batchToProcess.getWaiters().forEach(f -> f.completeExceptionally(e));
            }
        }
    }
}
