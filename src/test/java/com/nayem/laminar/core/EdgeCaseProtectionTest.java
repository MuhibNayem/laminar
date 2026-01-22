package com.nayem.laminar.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for edge case protections added to Laminar:
 * - Batch size limiting
 * - Cyclic coalescing detection
 * - Mutation versioning
 */
public class EdgeCaseProtectionTest {

    /**
     * Tests that batch size limiting prevents memory exhaustion.
     * Batch size is based on number of waiters (CompletableFutures), not add count.
     */
    @Test
    public void testBatchSizeLimiting() {
        CoalescingBatch<AtomicInteger> batch = new CoalescingBatch<>(
                new TestMutation("entity1", 1), 5, 1000); // Limit to 5 waiters

        // Add 4 waiters (below limit of 5)
        for (int i = 0; i < 4; i++) {
            batch.addWaiter(new CompletableFuture<>());
        }

        // Add mutations while under waiter limit (these should work)
        batch.add(new TestMutation("entity1", 1));
        batch.add(new TestMutation("entity1", 1));

        // Add 5th waiter to reach the limit
        batch.addWaiter(new CompletableFuture<>());

        // This add should still work (at limit, not over)
        batch.add(new TestMutation("entity1", 1));

        // Add 6th waiter to exceed limit (now waiters.size() = 6 >= 5)
        batch.addWaiter(new CompletableFuture<>());

        // Next add should fail because waiters.size() (6) >= maxBatchSize (5)
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> batch.add(new TestMutation("entity1", 1)));
        assertTrue(exception.getMessage().contains("Batch size limit exceeded"));
    }

    /**
     * Tests that cyclic coalescing detection prevents infinite loops.
     * Cyclic coalescing is when coalesce() returns the same instance consecutively.
     */
    @Test
    public void testCyclicCoalescingDetection() {
        // Test directly on CoalescingBatch for predictable behavior
        CoalescingBatch<AtomicInteger> batch = new CoalescingBatch<>(
                new CyclicMutation("entity1", 1), 10000, 10); // Low iteration limit

        // CyclicMutation.coalesce() returns 'this', so consecutive adds should trigger
        // detection
        for (int i = 0; i < 9; i++) {
            batch.add(new CyclicMutation("entity1", 1)); // These should work
        }

        // The 10th add should trigger cyclic detection
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> batch.add(new CyclicMutation("entity1", 1)));
        assertTrue(exception.getMessage().contains("Cyclic coalescing detected"));
        assertTrue(exception.getMessage().contains("10 consecutive same-instance returns"));
    }

    /**
     * Tests that versioned mutations are properly tracked
     */
    @Test
    public void testVersionedMutation() {
        VersionedTestMutation v1 = new VersionedTestMutation("entity1", 1, 1);
        VersionedTestMutation v2 = new VersionedTestMutation("entity1", 2, 2);

        assertEquals(1, v1.getVersion());
        assertEquals(2, v2.getVersion());

        // Verify versioned mutation works with coalescing
        Mutation<AtomicInteger> coalesced = v1.coalesce(v2);
        assertTrue(coalesced instanceof VersionedTestMutation);
        assertEquals(2, ((VersionedTestMutation) coalesced).getVersion());
    }

    /**
     * Tests batch metrics tracking.
     * Coalescence count only increments for consecutive same-instance returns.
     */
    @Test
    public void testBatchMetrics() {
        // Use CyclicMutation which returns 'this' to test coalescence count
        CoalescingBatch<AtomicInteger> batch = new CoalescingBatch<>(
                new CyclicMutation("entity1", 1), 100, 1000);

        assertEquals(0, batch.getCoalescenceCount());
        assertEquals(0, batch.getBatchSize());

        // CyclicMutation.coalesce() returns 'this', so coalescence count should
        // increment
        batch.add(new CyclicMutation("entity1", 2));
        assertEquals(1, batch.getCoalescenceCount());

        batch.addWaiter(new CompletableFuture<>());
        assertEquals(1, batch.getBatchSize());

        // TestMutation returns a new instance, so coalescence count should reset
        CoalescingBatch<AtomicInteger> batch2 = new CoalescingBatch<>(
                new TestMutation("entity1", 1), 100, 1000);
        batch2.add(new TestMutation("entity1", 2));
        assertEquals(0, batch2.getCoalescenceCount()); // Reset because new instance returned
    }

    // Test mutations

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

    /**
     * Mutation that always returns 'this' in coalesce() - demonstrates cyclic
     * coalescing
     */
    static class CyclicMutation implements Mutation<AtomicInteger> {
        private final String entityKey;
        private final int amount;

        public CyclicMutation(String entityKey, int amount) {
            this.entityKey = entityKey;
            this.amount = amount;
        }

        @Override
        public String getEntityKey() {
            return entityKey;
        }

        @Override
        public Mutation<AtomicInteger> coalesce(Mutation<AtomicInteger> other) {
            // BAD: Always returns 'this' - creates infinite loop
            return this;
        }

        @Override
        public void apply(AtomicInteger entity) {
            entity.addAndGet(amount);
        }
    }

    /**
     * Mutation that implements versioning
     */
    static class VersionedTestMutation implements Mutation<AtomicInteger>, VersionedMutation {
        private final String entityKey;
        private final int amount;
        private final int version;

        public VersionedTestMutation(String entityKey, int amount, int version) {
            this.entityKey = entityKey;
            this.amount = amount;
            this.version = version;
        }

        @Override
        public String getEntityKey() {
            return entityKey;
        }

        @Override
        public int getVersion() {
            return version;
        }

        @Override
        public Mutation<AtomicInteger> coalesce(Mutation<AtomicInteger> other) {
            if (other instanceof VersionedTestMutation next) {
                // Use newer version
                int newVersion = Math.max(this.version, next.version);
                return new VersionedTestMutation(entityKey, this.amount + next.amount, newVersion);
            }
            return other;
        }

        @Override
        public void apply(AtomicInteger entity) {
            entity.addAndGet(amount);
        }
    }
}
