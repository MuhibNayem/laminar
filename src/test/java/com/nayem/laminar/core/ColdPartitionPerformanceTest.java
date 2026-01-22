package com.nayem.laminar.core;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;

public class ColdPartitionPerformanceTest {

    @Test
    void benchmarkColdVsHot() {
        int requestCount = 20_000;

        System.out.println("=== Benchmarking: " + requestCount + " requests ===");

        long coldTime = runTest(requestCount, true);
        System.out.println("Cold Partition (Unique Keys): " + coldTime + "ms");

        long hotTime = runTest(requestCount, false);
        System.out.println("Hot Partition (Single Key):   " + hotTime + "ms");

        System.out.println("Overhead Factor: " + String.format("%.2fx", (double) coldTime / hotTime));
    }

    private long runTest(int count, boolean uniqueKeys) {
        ConcurrentHashMap<String, Integer> db = new ConcurrentHashMap<>();
        AtomicInteger writeCount = new AtomicInteger();

        LaminarEngine<Integer> engine = LaminarEngine.<Integer>builder()
                .loader(k -> 0)
                .saver(e -> {
                    writeCount.incrementAndGet();
                    try {
                        Thread.sleep(1);
                    } catch (Exception ex) {
                    }
                })
                .maxWaiters(count * 2)
                .maxCachedWorkers(uniqueKeys ? count : 100)
                .build();

        List<CompletableFuture<Void>> futures = new ArrayList<>(count);
        long start = System.currentTimeMillis();

        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
                .newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                String key = uniqueKeys ? "key-" + i : "hot-key";
                futures.add(engine.dispatch(new SimpleMutation(key)));
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long end = System.currentTimeMillis();

        engine.close();

        System.out.println("  -> Actual DB Writes: " + writeCount.get());
        return end - start;
    }

    static class SimpleMutation implements Mutation<Integer> {
        private final String key;

        public SimpleMutation(String key) {
            this.key = key;
        }

        public String getEntityKey() {
            return key;
        }

        public void apply(Integer entity) {
        }

        public Mutation<Integer> coalesce(Mutation<Integer> other) {
            return other;
        }
    }
}
