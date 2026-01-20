package com.nayem.laminar.worker;

import com.nayem.laminar.core.CoalescingBatch;
import com.nayem.laminar.core.Mutation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Handles the "Actor" logic for a single Entity Key.
 * <p>
 * It manages the lifecycle of the Virtual Thread:
 * - Spawns when work arrives.
 * </p>
 */
public class EntityWorker<T> {
    private final String key;
    private final Consumer<CoalescingBatch<T>> processor;

    private final ReentrantLock lock = new ReentrantLock();
    private CoalescingBatch<T> pendingBatch;
    private volatile boolean isRunning;

    public boolean isRunning() {
        return isRunning;
    }

    private final int maxWaiters;
    private final String threadNamePrefix;
    private final java.util.concurrent.ExecutorService executor;

    public EntityWorker(String key, Consumer<CoalescingBatch<T>> processor, int maxWaiters, String threadNamePrefix,
            java.util.concurrent.ExecutorService executor) {
        this.key = key;
        this.processor = processor;
        this.maxWaiters = maxWaiters;
        this.threadNamePrefix = threadNamePrefix;
        this.executor = executor;
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
                pendingBatch = new CoalescingBatch<>(mutation);
            } else {
                pendingBatch.add(mutation);
            }
            pendingBatch.addWaiter(future);

            if (!isRunning) {
                isRunning = true;
                // Use shared executor to avoid thread name string building overhead
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
                batchToProcess.getWaiters().forEach(f -> f.completeExceptionally(e));
            }
        }
    }
}
