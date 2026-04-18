package com.nayem.laminar.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    void rejectsSubMillisecondWindowConfiguration() {
        assertThrows(IllegalArgumentException.class,
            () -> new TokenBucketRateLimiter(100, Duration.ZERO, 100));
    }

    @Test
    void doesNotRefillAtThousandTimesConfiguredRate() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, Duration.ofSeconds(1), 10);
        String key = "user-1";

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allowRequest(key));
        }
        assertFalse(limiter.allowRequest(key));

        Thread.sleep(10);
        assertTrue(limiter.getAvailableTokens(key) <= 1, "refill rate should not be 1000x");

        Thread.sleep(120);
        assertTrue(limiter.allowRequest(key), "should refill roughly one token after ~100ms");
    }
}
