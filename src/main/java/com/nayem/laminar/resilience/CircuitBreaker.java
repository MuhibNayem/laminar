package com.nayem.laminar.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Circuit Breaker pattern implementation for fault tolerance.
 * <p>
 * Prevents cascading failures by failing fast when a service is unavailable.
 * The circuit breaker has three states:
 * </p>
 * <ul>
 *   <li><strong>CLOSED</strong>: Normal operation, requests pass through</li>
 *   <li><strong>OPEN</strong>: Service is failing, requests fail immediately</li>
 *   <li><strong>HALF_OPEN</strong>: Testing if service recovered, limited requests allowed</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * CircuitBreaker breaker = new CircuitBreaker.Builder()
 *     .failureThreshold(5)
 *     .successThreshold(3)
 *     .timeout(Duration.ofSeconds(30))
 *     .build();
 * 
 * try {
 *     String result = breaker.executeSupplier(() -> callExternalService());
 * } catch (CircuitBreaker.CircuitBreakerOpenException e) {
 *     // Fail fast or use fallback
 *     return getFallbackValue();
 * }
 * }</pre>
 * 
 * <h2>Thread Safety</h2>
 * <p>
 * This implementation is thread-safe and suitable for concurrent access.
 * </p>
 */
public class CircuitBreaker {
    
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
    
    private final int failureThreshold;
    private final int successThreshold;
    private final Duration timeout;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private volatile State state = State.CLOSED;
    
    /**
     * Private constructor - use {@link Builder} to create instances.
     */
    private CircuitBreaker(Builder builder) {
        this.failureThreshold = builder.failureThreshold;
        this.successThreshold = builder.successThreshold;
        this.timeout = builder.timeout;
    }
    
    /**
     * Executes a supplier function with circuit breaker protection.
     *
     * @param supplier the function to execute
     * @param <T> the return type
     * @return the result from the supplier
     * @throws CircuitBreakerOpenException if the circuit is open
     * @throws RuntimeException if the supplier throws an exception
     */
    public <T> T executeSupplier(Supplier<T> supplier) {
        if (!allowRequest()) {
            throw new CircuitBreakerOpenException("Circuit breaker is OPEN");
        }
        
        try {
            T result = supplier.get();
            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }
    
    /**
     * Executes a runnable function with circuit breaker protection.
     *
     * @param runnable the function to execute
     * @throws CircuitBreakerOpenException if the circuit is open
     * @throws RuntimeException if the runnable throws an exception
     */
    public void executeRunnable(Runnable runnable) {
        if (!allowRequest()) {
            throw new CircuitBreakerOpenException("Circuit breaker is OPEN");
        }
        
        try {
            runnable.run();
            recordSuccess();
        } catch (Exception e) {
            recordFailure();
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }
    
    /**
     * Checks if a request should be allowed based on current state.
     *
     * @return true if request is allowed, false otherwise
     */
    public synchronized boolean allowRequest() {
        switch (state) {
            case CLOSED:
                return true;
                
            case OPEN:
                // Check if timeout has elapsed to transition to HALF_OPEN
                long elapsed = System.currentTimeMillis() - lastFailureTime.get();
                if (elapsed >= timeout.toMillis()) {
                    state = State.HALF_OPEN;
                    successCount.set(0);
                    return true;
                }
                return false;
                
            case HALF_OPEN:
                // Allow limited requests in HALF_OPEN state
                return true;
                
            default:
                return false;
        }
    }
    
    /**
     * Records a successful execution.
     */
    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            int count = successCount.incrementAndGet();
            if (count >= successThreshold) {
                state = State.CLOSED;
                failureCount.set(0);
                successCount.set(0);
            }
        } else if (state == State.CLOSED) {
            // Reset failure count on success in CLOSED state
            failureCount.set(0);
        }
    }
    
    /**
     * Records a failed execution.
     */
    public synchronized void recordFailure() {
        lastFailureTime.set(System.currentTimeMillis());
        
        if (state == State.HALF_OPEN) {
            // Any failure in HALF_OPEN immediately opens the circuit
            state = State.OPEN;
            successCount.set(0);
        } else if (state == State.CLOSED) {
            int count = failureCount.incrementAndGet();
            if (count >= failureThreshold) {
                state = State.OPEN;
            }
        }
    }
    
    /**
     * Gets the current state of the circuit breaker.
     *
     * @return the current state
     */
    public State getState() {
        return state;
    }
    
    /**
     * Gets current statistics about the circuit breaker.
     *
     * @return map containing statistics
     */
    public java.util.Map<String, Object> getStats() {
        return java.util.Map.of(
            "state", state.name(),
            "failureCount", failureCount.get(),
            "successCount", successCount.get(),
            "failureThreshold", failureThreshold,
            "successThreshold", successThreshold,
            "timeoutMs", timeout.toMillis(),
            "lastFailureTime", Instant.ofEpochMilli(lastFailureTime.get()).toString()
        );
    }
    
    /**
     * Manually resets the circuit breaker to CLOSED state.
     */
    public synchronized void reset() {
        state = State.CLOSED;
        failureCount.set(0);
        successCount.set(0);
        lastFailureTime.set(0);
    }
    
    /**
     * Forces the circuit breaker to OPEN state.
     */
    public synchronized void forceOpen() {
        state = State.OPEN;
        lastFailureTime.set(System.currentTimeMillis());
    }
    
    /**
     * Builder for creating CircuitBreaker instances.
     */
    public static class Builder {
        private int failureThreshold = 5;
        private int successThreshold = 3;
        private Duration timeout = Duration.ofSeconds(30);
        
        /**
         * Sets the number of consecutive failures before opening the circuit.
         *
         * @param threshold the failure threshold
         * @return this builder
         */
        public Builder failureThreshold(int threshold) {
            this.failureThreshold = threshold;
            return this;
        }
        
        /**
         * Sets the number of consecutive successes in HALF_OPEN state before closing the circuit.
         *
         * @param threshold the success threshold
         * @return this builder
         */
        public Builder successThreshold(int threshold) {
            this.successThreshold = threshold;
            return this;
        }
        
        /**
         * Sets the timeout duration before transitioning from OPEN to HALF_OPEN.
         *
         * @param timeout the timeout duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }
        
        /**
         * Builds the CircuitBreaker instance.
         *
         * @return a new CircuitBreaker
         */
        public CircuitBreaker build() {
            return new CircuitBreaker(this);
        }
    }
    
    /**
     * Exception thrown when the circuit breaker is open.
     */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }
}
