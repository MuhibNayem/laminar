package com.nayem.laminar.resilience;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @Test
    void opensAfterFailureThresholdAndThenClosesAfterHalfOpenSuccesses() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker.Builder()
            .failureThreshold(2)
            .successThreshold(2)
            .timeout(Duration.ofMillis(50))
            .build();

        assertThrows(RuntimeException.class, () -> breaker.executeRunnable(() -> { throw new RuntimeException("fail-1"); }));
        assertThrows(RuntimeException.class, () -> breaker.executeRunnable(() -> { throw new RuntimeException("fail-2"); }));
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertThrows(CircuitBreaker.CircuitBreakerOpenException.class, () -> breaker.executeRunnable(() -> {}));

        Thread.sleep(60);
        breaker.executeRunnable(() -> {});
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());
        breaker.executeRunnable(() -> {});
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    void reopensImmediatelyWhenHalfOpenAttemptFails() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker.Builder()
            .failureThreshold(1)
            .successThreshold(2)
            .timeout(Duration.ofMillis(30))
            .build();

        assertThrows(RuntimeException.class, () -> breaker.executeRunnable(() -> { throw new RuntimeException("initial-failure"); }));
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        Thread.sleep(40);
        assertThrows(RuntimeException.class, () -> breaker.executeRunnable(() -> { throw new RuntimeException("half-open-failure"); }));
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
    }
}
