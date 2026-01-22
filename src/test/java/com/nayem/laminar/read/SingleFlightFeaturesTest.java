package com.nayem.laminar.read;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SingleFlightFeaturesTest {

    @Test
    void testIsInFlight() throws InterruptedException {
        SingleFlightGroup<String> singleFlight = new SingleFlightGroup<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Start a long-running task
        CompletableFuture<String> future = singleFlight.doCall("key1", () -> {
            try {
                latch.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done";
        });

        // Verify it is in flight
        assertTrue(singleFlight.isInFlight("key1"), "Request should be in flight");
        assertFalse(singleFlight.isInFlight("key2"), "Unrelated key should not be in flight");

        // Finish the task
        latch.countDown();
        future.join();

        // Verify it is no longer in flight
        // Note: There's a tiny race condition between future completion and map removal in 'finally' block
        // So we wait a bit
        Thread.sleep(50);
        assertFalse(singleFlight.isInFlight("key1"), "Request should no longer be in flight");
    }

    @Test
    void testCancel() throws InterruptedException {
        SingleFlightGroup<String> singleFlight = new SingleFlightGroup<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean wasCancelled = new AtomicBoolean(false);

        // Start a task that waits
        CompletableFuture<String> future = singleFlight.doCall("cancelKey", () -> {
            try {
                startLatch.countDown();
                Thread.sleep(5000); // Simulate long work
                return "completed";
            } catch (InterruptedException e) {
                wasCancelled.set(true);
                return "interrupted";
            }
        });

        // Wait for task to start
        startLatch.await();
        assertTrue(singleFlight.isInFlight("cancelKey"));

        // Cancel it
        boolean cancelled = singleFlight.cancel("cancelKey");
        assertTrue(cancelled, "Cancel should return true for in-flight request");

        // Verify future throws CancellationException
        assertThrows(java.util.concurrent.CancellationException.class, future::join);

        // Verify it is removed from map
        assertFalse(singleFlight.isInFlight("cancelKey"), "Cancelled request should be removed from map");
        
        // Note: The underlying thread interrupt logic depends on the executor. 
        // SingleFlightGroup uses Thread.ofVirtual().start(), which respects interrupts during sleep.
        // We give it a moment to process the interrupt.
        Thread.sleep(100);
        assertTrue(wasCancelled.get(), "Underlying task should have been interrupted");
    }
}
