package com.nayem.laminar.worker;

import com.nayem.laminar.core.CoalescingBatch;
import com.nayem.laminar.core.Mutation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EntityWorker edge cases:
 * - Rapid shutdown during active batch
 * - Concurrent submit race conditions
 * - Backpressure enforcement
 */
public class EntityWorkerTest {

    private ExecutorService executor;
    private List<CoalescingBatch<AtomicInteger>> processedBatches;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        processedBatches = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /**
     * Tests that backpressure is enforced when maxWaiters is exceeded.
     */
    @Test
    void testBackpressureEnforcement() throws Exception {
        Consumer<CoalescingBatch<AtomicInteger>> slowProcessor = batch -> {
            try {
                // Simulate slow processing
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            processedBatches.add(batch);
        };

        EntityWorker<AtomicInteger> worker = new EntityWorker<>(
                "entity1", slowProcessor, 5, executor);

        // Submit first mutation to start processing
        worker.submit(new TestMutation("entity1", 1));

        // Wait a bit for processing to start
        Thread.sleep(50);

        // Submit mutations up to maxWaiters (5)
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            futures.add(worker.submit(new TestMutation("entity1", 1)));
        }

        // The 6th submission should be rejected
        CompletableFuture<Void> rejected = worker.submit(new TestMutation("entity1", 1));

        ExecutionException exception = assertThrows(ExecutionException.class, rejected::get);
        assertTrue(exception.getCause() instanceof RejectedExecutionException);
        assertTrue(exception.getCause().getMessage().contains("Backpressure"));
    }

    /**
     * Tests concurrent submit from multiple threads.
     */
    @Test
    void testConcurrentSubmit() throws Exception {
        AtomicInteger processCount = new AtomicInteger(0);

        Consumer<CoalescingBatch<AtomicInteger>> processor = batch -> {
            processCount.incrementAndGet();
            processedBatches.add(batch);
        };

        EntityWorker<AtomicInteger> worker = new EntityWorker<>(
                "entity1", processor, 10000, executor);

        int numThreads = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        List<CompletableFuture<Void>> allFutures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final int val = i;
            executor.execute(() -> {
                try {
                    startLatch.await();
                    allFutures.add(worker.submit(new TestMutation("entity1", val)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);

        // Wait for all futures to complete
        Thread.sleep(200);

        for (CompletableFuture<Void> f : allFutures) {
            assertDoesNotThrow(() -> f.get(1, TimeUnit.SECONDS));
        }

        // Verify at least one batch was processed
        assertFalse(processedBatches.isEmpty());
    }

    /**
     * Tests that worker handles processor exceptions correctly.
     */
    @Test
    void testProcessorExceptionPropagation() throws Exception {
        RuntimeException testException = new RuntimeException("Test processor failure");

        Consumer<CoalescingBatch<AtomicInteger>> failingProcessor = batch -> {
            throw testException;
        };

        EntityWorker<AtomicInteger> worker = new EntityWorker<>(
                "entity1", failingProcessor, 1000, executor);

        CompletableFuture<Void> future = worker.submit(new TestMutation("entity1", 1));

        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> future.get(2, TimeUnit.SECONDS));
        assertEquals(testException, exception.getCause());
    }

    /**
     * Tests that worker correctly reports running status.
     */
    @Test
    void testRunningStatus() throws Exception {
        CountDownLatch processingStarted = new CountDownLatch(1);
        CountDownLatch allowComplete = new CountDownLatch(1);

        Consumer<CoalescingBatch<AtomicInteger>> blockingProcessor = batch -> {
            processingStarted.countDown();
            try {
                allowComplete.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        EntityWorker<AtomicInteger> worker = new EntityWorker<>(
                "entity1", blockingProcessor, 1000, executor);

        assertFalse(worker.isRunning());

        worker.submit(new TestMutation("entity1", 1));
        processingStarted.await(1, TimeUnit.SECONDS);

        assertTrue(worker.isRunning());

        allowComplete.countDown();
        Thread.sleep(100);

        assertFalse(worker.isRunning());
    }

    // Test mutation implementation
    static class TestMutation implements Mutation<AtomicInteger> {
        private final String entityKey;
        private final int amount;

        public TestMutation(String entityKey, int amount) {
            this.entityKey = entityKey;
            this.amount = amount;
        }

        @Override
        public String getEntityKey() {
            return entityKey;
        }

        @Override
        public Mutation<AtomicInteger> coalesce(Mutation<AtomicInteger> other) {
            if (other instanceof TestMutation next) {
                return new TestMutation(entityKey, this.amount + next.amount);
            }
            return other;
        }

        @Override
        public void apply(AtomicInteger entity) {
            entity.addAndGet(amount);
        }
    }
}
