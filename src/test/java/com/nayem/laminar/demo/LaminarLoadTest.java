package com.nayem.laminar.demo;

import com.nayem.laminar.core.LaminarEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LaminarLoadTest {

    @Test
    public void testHotPartitionMeltdownPrevention() throws Exception {
        AtomicLong dbState = new AtomicLong(0);
        AtomicInteger dbCallCount = new AtomicInteger(0);

        LaminarEngine<AtomicLong> engine = LaminarEngine.<AtomicLong>builder()
                .loader(key -> dbState)
                .saver(entity -> {
                    try {
                        Thread.sleep(10);
                        dbCallCount.incrementAndGet();
                        System.out.println("DB Write: " + entity.get() + " XP");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .maxWaiters(150_000)
                .maxBatchSize(200_000) // Allow large batches for load testing
                .build();

        int REQUEST_COUNT = 150_000;
        java.util.Queue<CompletableFuture<Void>> clientFutures = new java.util.concurrent.ConcurrentLinkedQueue<>();

        long startTime = System.currentTimeMillis();

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < REQUEST_COUNT; i++) {
                executor.submit(() -> {
                    CompletableFuture<Void> f = engine.dispatch(new XPMutation("user_1", 1));
                    clientFutures.add(f);
                });
            }
        }

        CompletableFuture.allOf(clientFutures.toArray(new CompletableFuture[0])).join();

        long endTime = System.currentTimeMillis();

        System.out.println("==========================================");
        System.out.println("Total Requests In: " + REQUEST_COUNT);
        System.out.println("Actual DB Writes:  " + dbCallCount.get());
        System.out.println("Final DB State:    " + dbState.get());
        System.out.println("Time Taken:        " + (endTime - startTime) + "ms");
        System.out.println("Coalescing Factor: " + (double) REQUEST_COUNT / dbCallCount.get() + "x");
        System.out.println("==========================================");

        assertEquals(REQUEST_COUNT, dbState.get(), "Final state should be exactly 10,000");
        assert (dbCallCount.get() < 500);
    }
}
