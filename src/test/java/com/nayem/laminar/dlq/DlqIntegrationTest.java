package com.nayem.laminar.dlq;

import com.nayem.laminar.core.LaminarEngine;
import com.nayem.laminar.core.Mutation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class DlqIntegrationTest {

    @Test
    public void testFailedMutationSentToDlq() throws Exception {
        InMemoryDeadLetterQueue<AtomicInteger> dlq = new InMemoryDeadLetterQueue<>();

        LaminarEngine<AtomicInteger> engine = LaminarEngine.<AtomicInteger>builder()
                .loader(key -> new AtomicInteger(0))
                .saver(entity -> {
                    throw new RuntimeException("Simulated Db Failure");
                })
                .dlq(dlq)
                .build();

        CompletableFuture<Void> future = engine.dispatch(new TestMutation("entity1", 1));

        // It should complete exceptionally
        assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));

        // Verify DLQ content
        assertEquals(1, dlq.size(), "DLQ should have 1 entry");
        Optional<DeadLetterQueue.DlqEntry<AtomicInteger>> entry = dlq.peek();
        assertTrue(entry.isPresent());
        assertEquals("entity1", entry.get().entityKey());
        assertEquals("Simulated Db Failure", entry.get().errorMessage());

        engine.close();
    }

    static class TestMutation implements Mutation<AtomicInteger> {
        private final String key;
        private final int val;

        public TestMutation(String key, int val) {
            this.key = key;
            this.val = val;
        }

        @Override
        public String getEntityKey() {
            return key;
        }

        @Override // Coalesce logic
        public Mutation<AtomicInteger> coalesce(Mutation<AtomicInteger> other) {
            return other;
        }

        @Override
        public void apply(AtomicInteger entity) {
            entity.addAndGet(val);
        }
    }
}
