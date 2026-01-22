package com.nayem.laminar.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class FunctionalMutationTest {

    @Test
    void testSimpleMutationAppliesLogic() {
        AtomicLong counter = new AtomicLong(0);

        FunctionalMutation<AtomicLong> mutation = FunctionalMutation.of(
                "entity-1",
                entity -> entity.addAndGet(10));

        assertEquals("entity-1", mutation.getEntityKey());
        mutation.apply(counter);
        assertEquals(10, counter.get());
    }

    @Test
    void testNonCoalescingMutationReturnsNext() {
        FunctionalMutation<AtomicLong> mutation1 = FunctionalMutation.of(
                "entity-1",
                entity -> entity.addAndGet(10));

        FunctionalMutation<AtomicLong> mutation2 = FunctionalMutation.of(
                "entity-1",
                entity -> entity.addAndGet(20));

        // Non-coalescing mutation should return 'other'
        Mutation<AtomicLong> result = mutation1.coalesce(mutation2);
        assertSame(mutation2, result);
    }

    @Test
    void testCoalescingMutationMergesLogic() {
        FunctionalMutation<AtomicLong> mutation1 = FunctionalMutation.of(
                "entity-1",
                entity -> entity.addAndGet(10),
                (current, next) -> {
                    // Custom coalescing: return a new mutation that combines both
                    return FunctionalMutation.of(
                            "entity-1",
                            entity -> entity.addAndGet(30) // Pretend we merged 10 + 20
                    );
                });

        FunctionalMutation<AtomicLong> mutation2 = FunctionalMutation.of(
                "entity-1",
                entity -> entity.addAndGet(20));

        Mutation<AtomicLong> merged = mutation1.coalesce(mutation2);
        assertNotSame(mutation1, merged);
        assertNotSame(mutation2, merged);

        // Verify the merged mutation applies correctly
        AtomicLong counter = new AtomicLong(0);
        merged.apply(counter);
        assertEquals(30, counter.get());
    }
}
