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

        java.util.function.Supplier<String> expensiveOp = () -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            callCount.incrementAndGet();
            return "Result";
        };

        int THREADS = 100;
        CompletableFuture<?>[] futures = new CompletableFuture[THREADS];

        for (int i = 0; i < THREADS; i++) {
            futures[i] = singleFlight.doCall("key_1", expensiveOp);
        }

        CompletableFuture.allOf(futures).join();

        assertEquals(1, callCount.get(), "Supplier should have only executed once");
    }
}
