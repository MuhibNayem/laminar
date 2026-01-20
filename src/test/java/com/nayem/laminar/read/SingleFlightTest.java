package com.nayem.laminar.read;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SingleFlightTest {

    @Test
    public void testThunderingHerdProtection() {
        SingleFlightGroup<String> singleFlight = new SingleFlightGroup<>();
        AtomicInteger callCount = new AtomicInteger(0);

        // Slow supplier that simulates DB fetch
        java.util.function.Supplier<String> expensiveOp = () -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            callCount.incrementAndGet();
            return "Result";
        };

        // Fire 100 concurrent requests
        int THREADS = 100;
        CompletableFuture<?>[] futures = new CompletableFuture[THREADS];

        for (int i = 0; i < THREADS; i++) {
            futures[i] = singleFlight.doCall("key_1", expensiveOp);
        }

        // Wait for all
        CompletableFuture.allOf(futures).join();

        // Verification:
        // Although 100 requests were made, the Supplier should have run exactly ONCE.
        assertEquals(1, callCount.get(), "Supplier should have only executed once");
    }
}
