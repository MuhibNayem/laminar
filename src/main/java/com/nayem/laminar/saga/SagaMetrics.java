package com.nayem.laminar.saga;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/**
 * Prometheus metrics for Saga orchestration health and performance monitoring.
 */
public class SagaMetrics {

    private static final Logger log = LoggerFactory.getLogger(SagaMetrics.class);

    private final Map<SagaStatus, AtomicLong> activeSagasByStatus;
    private final Timer sagaDurationTimer;
    private final Timer stepExecutionTimer;
    private final Counter compensationCounter;
    private final Counter recoveryAttemptCounter;
    private final Counter timeoutCounter;
    private final Counter failedSagaCounter;
    private final Counter successfulSagaCounter;

    public SagaMetrics(MeterRegistry registry) {
        this.activeSagasByStatus = new ConcurrentHashMap<>();

        if (registry != null) {
            for (SagaStatus status : SagaStatus.values()) {
                AtomicLong count = new AtomicLong(0);
                activeSagasByStatus.put(status, count);
                
                Gauge.builder("laminar.saga.active", count, AtomicLong::get)
                        .description("Number of active sagas by status")
                        .tag("status", status.name().toLowerCase())
                        .register(registry);
            }

            this.sagaDurationTimer = Timer.builder("laminar.saga.duration")
                    .description("Total saga execution duration")
                    .register(registry);

            this.stepExecutionTimer = Timer.builder("laminar.saga.step.execution")
                    .description("Individual step execution duration")
                    .register(registry);

            this.compensationCounter = Counter.builder("laminar.saga.compensation.count")
                    .description("Number of compensation triggers")
                    .register(registry);

            this.recoveryAttemptCounter = Counter.builder("laminar.saga.recovery.attempts")
                    .description("Number of saga recovery attempts")
                    .register(registry);

            this.timeoutCounter = Counter.builder("laminar.saga.timeout.count")
                    .description("Number of saga timeouts")
                    .register(registry);

            this.failedSagaCounter = Counter.builder("laminar.saga.failed")
                    .description("Number of failed sagas")
                    .register(registry);

            this.successfulSagaCounter = Counter.builder("laminar.saga.successful")
                    .description("Number of successful sagas")
                    .register(registry);
        } else {
            this.sagaDurationTimer = null;
            this.stepExecutionTimer = null;
            this.compensationCounter = null;
            this.recoveryAttemptCounter = null;
            this.timeoutCounter = null;
            this.failedSagaCounter = null;
            this.successfulSagaCounter = null;
        }
    }

    public void recordStatusChange(SagaStatus oldStatus, SagaStatus newStatus) {
        if (oldStatus != null && activeSagasByStatus.containsKey(oldStatus)) {
            activeSagasByStatus.get(oldStatus).decrementAndGet();
        }
        if (newStatus != null && activeSagasByStatus.containsKey(newStatus)) {
            activeSagasByStatus.get(newStatus).incrementAndGet();
        }
    }

    public void recordSagaDuration(long durationMs, String status) {
        if (sagaDurationTimer != null) {
            sagaDurationTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    public void recordStepDuration(long durationMs, String stepId) {
        if (stepExecutionTimer != null) {
            stepExecutionTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    public void recordCompletedSaga() {
        if (successfulSagaCounter != null) {
            successfulSagaCounter.increment();
        }
    }

    public void recordFailedSaga() {
        if (failedSagaCounter != null) {
            failedSagaCounter.increment();
        }
    }

    public void recordTimedOutSaga() {
        if (timeoutCounter != null) {
            timeoutCounter.increment();
        }
    }

    public void recordCompensationTriggered() {
        if (compensationCounter != null) {
            compensationCounter.increment();
        }
    }

    public void recordRecoveredSagas(int count) {
        if (recoveryAttemptCounter != null) {
            recoveryAttemptCounter.increment(count);
        }
    }

    public static SagaMetrics noOp() {
        return new SagaMetrics(null);
    }
}
