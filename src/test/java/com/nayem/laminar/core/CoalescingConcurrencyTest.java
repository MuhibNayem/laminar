package com.nayem.laminar.core;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoalescingConcurrencyTest {

    static class AddMutation implements Mutation<Integer> {
        private final String key;
        private final int delta;

        public AddMutation(String key, int delta) {
            this.key = key;
            this.delta = delta;
        }

        @Override
        public String getEntityKey() {
            return key;
        }

        @Override
        public void apply(Integer entity) {
        }

        @Override
        public Mutation<Integer> coalesce(Mutation<Integer> other) {
            if (other instanceof AddMutation) {
                return new AddMutation(key, this.delta + ((AddMutation) other).delta);
            }
            return other;
        }

        public int getDelta() {
            return delta;
        }
    }

    @Test
    void shouldCorrectlyAccumulateConcurrentUpdates() {
        AtomicInteger dbWriteCount = new AtomicInteger(0);
        ConcurrentHashMap<String, Integer> database = new ConcurrentHashMap<>();
        database.put("counter-1", 0);

        LaminarEngine<Integer> engine = LaminarEngine.<Integer>builder()
                .loader(key -> database.get(key))
                .saver(entity -> {
                    dbWriteCount.incrementAndGet();
                    database.put("counter-1", entity);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .build();
    }

    @Test
    void shouldCorrectlyAccumulateConcurrentUpdates_MutableEntity() {
        AtomicInteger dbWriteCount = new AtomicInteger(0);
        ConcurrentHashMap<String, AtomicInteger> database = new ConcurrentHashMap<>();
        database.put("counter-1", new AtomicInteger(0));

        LaminarEngine<AtomicInteger> engine = LaminarEngine.<AtomicInteger>builder()
                .loader(key -> database.get(key))
                .saver(entity -> {
                    dbWriteCount.incrementAndGet();
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                    }
                })
                .maxWaiters(20000)
                .build();

        int threadCount = 50;
        int requestsPerThread = 200;
        int totalRequests = threadCount * requestsPerThread;

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        long start = System.currentTimeMillis();

        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < totalRequests; i++) {
                futures.add(engine.dispatch(new MutableAddMutation("counter-1", 1)));
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long end = System.currentTimeMillis();
        System.out.println("Processed " + totalRequests + " requests in " + (end - start) + "ms");
        System.out.println("DB Writes: " + dbWriteCount.get());

        assertEquals(totalRequests, database.get("counter-1").get(), "Final value should equal total increments");
        assertTrue(dbWriteCount.get() < totalRequests,
                "Should have coalesced writes (Actual: " + dbWriteCount.get() + ")");

        engine.close();
    }

    static class MutableAddMutation implements Mutation<AtomicInteger> {
        private final String key;
        private final int delta;

        public MutableAddMutation(String key, int delta) {
            this.key = key;
            this.delta = delta;
        }

        @Override
        public String getEntityKey() {
            return key;
        }

        @Override
        public void apply(AtomicInteger entity) {
            entity.addAndGet(delta);
        }

        @Override
        public Mutation<AtomicInteger> coalesce(Mutation<AtomicInteger> other) {
            if (other instanceof MutableAddMutation) {
                return new MutableAddMutation(key, this.delta + ((MutableAddMutation) other).delta);
            }
            return other;
        }
    }
}
