package com.nayem.laminar.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayem.laminar.core.LaminarDispatcher;
import com.nayem.laminar.spring.LaminarProperties;
import com.nayem.laminar.spring.LaminarRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates Saga execution asynchronously using LaminarEngine for batching.
 * <p>
 * Supports retry with exponential backoff before triggering compensation.
 * </p>
 */
public class SagaOrchestrator<T> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final SagaStateRepository stateRepository;
    private final LaminarRegistry registry;
    private final Duration retryBackoff;
    private final int maxRetries;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper;
    private final LaminarProperties.Saga.Recovery recoveryConfig;
    private final SagaMetrics metrics;
    private final SagaRecoveryLock recoveryLock;
    private final BackoffStrategy backoffStrategy;
    private final Duration sagaTimeout;

    public SagaOrchestrator(
            SagaStateRepository stateRepository,
            LaminarRegistry registry,
            Duration retryBackoff,
            int maxRetries,
            double jitterPercent,
            LaminarProperties.Saga.Recovery recoveryConfig,
            SagaMetrics metrics,
            SagaRecoveryLock recoveryLock,
            ObjectMapper objectMapper,
            Duration sagaTimeout) {
        this.stateRepository = stateRepository;
        this.registry = registry;
        this.retryBackoff = retryBackoff;
        this.maxRetries = maxRetries;
        this.objectMapper = objectMapper;
        this.recoveryConfig = recoveryConfig;
        this.metrics = metrics;
        this.recoveryLock = recoveryLock;
        this.sagaTimeout = sagaTimeout;
        this.backoffStrategy = new BackoffStrategy(jitterPercent);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();

        if (recoveryConfig.isEnabled()) {
            startRecovery();
        }
    }

    public CompletableFuture<Void> execute(LaminarSaga<T> saga) {
        metrics.recordStatusChange(null, SagaStatus.PENDING);
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.submit(() -> startSaga(saga, future));
        return future;
    }

    private void startSaga(LaminarSaga<T> saga, CompletableFuture<Void> completionFuture) {
        String sagaId = saga.getSagaId();
        long startTime = System.currentTimeMillis();

        if (stateRepository.exists(sagaId)) {
            var existingState = stateRepository.findById(sagaId).orElseThrow();
            if (existingState.status() == SagaStatus.COMPLETED) {
                completionFuture.complete(null);
                metrics.recordSagaDuration(System.currentTimeMillis() - startTime, "success");
                metrics.recordCompletedSaga();
                metrics.recordStatusChange(SagaStatus.PENDING, SagaStatus.COMPLETED);
                return;
            }
            if (existingState.status() == SagaStatus.FAILED) {
                completionFuture.completeExceptionally(new RuntimeException(existingState.failureReason()));
                metrics.recordSagaDuration(System.currentTimeMillis() - startTime, "failed");
                metrics.recordFailedSaga();
                metrics.recordStatusChange(SagaStatus.PENDING, SagaStatus.FAILED);
                return;
            }
            stateRepository.delete(sagaId);
        }

        try {
            String serializedSaga = objectMapper.writeValueAsString(saga);
            SagaState state = SagaState.initial(sagaId, saga.getClass().getName(), serializedSaga);
            state = state.withStatus(SagaStatus.RUNNING);
            stateRepository.save(state);
            metrics.recordStatusChange(SagaStatus.PENDING, SagaStatus.RUNNING);

            // Timeout enforcement check
            executor.submit(() -> {
               try {
                   TimeUnit.MILLISECONDS.sleep(sagaTimeout.toMillis());
                   var checkState = stateRepository.findById(sagaId);
                   if (checkState.isPresent() && checkState.get().status() == SagaStatus.RUNNING) {
                       log.warn("Saga {} timed out after {}ms", sagaId, sagaTimeout.toMillis());
                       metrics.recordTimedOutSaga();
                       triggerCompensation(saga, checkState.get(), new java.util.concurrent.TimeoutException("Saga timed out"), completionFuture);
                   }
               } catch (InterruptedException e) {
                   Thread.currentThread().interrupt();
               }
            });

            advanceSaga(saga, state, completionFuture);
        } catch (Exception e) {
            completionFuture.completeExceptionally(e);
            metrics.recordFailedSaga();
            metrics.recordStatusChange(SagaStatus.RUNNING, SagaStatus.FAILED);
        }
    }

    private void advanceSaga(LaminarSaga<T> saga, SagaState state, CompletableFuture<Void> completionFuture) {
        int nextIndex = state.currentStepIndex() + 1;
        List<SagaStep<T>> steps = saga.getSteps();

        if (nextIndex >= steps.size()) {
            completeSuccess(saga, state, completionFuture);
            return;
        }

        SagaStep<T> step = steps.get(nextIndex);

        LaminarDispatcher<T> dispatcher = registry.getDispatcher(saga.getEntityType());
        if (dispatcher == null) {
            throw new IllegalStateException("No LaminarDispatcher found for " + saga.getEntityType());
        }

        long stepStartTime = System.currentTimeMillis();
        SagaStepMutation<T> mutation = new SagaStepMutation<>(
                step,
                saga.getSagaId(),
                stateRepository,
                (result) -> {
                    metrics.recordStepDuration(System.currentTimeMillis() - stepStartTime, step.getStepId());
                    executor.submit(() -> {
                        try {
                            SagaState nextState = stateRepository.findById(saga.getSagaId())
                                    .orElseThrow(() -> new IllegalStateException("Saga state disappeared"))
                                    .withStepIndex(nextIndex);

                            stateRepository.save(nextState);

                            log.debug("Saga {} step {} completed", saga.getSagaId(), step.getStepId());
                            advanceSaga(saga, nextState, completionFuture);
                        } catch (Exception e) {
                            handleStepFailure(saga, state, e, completionFuture);
                        }
                    });
                },
                (error) -> {
                    executor.submit(() -> handleStepFailure(saga, state, error, completionFuture));
                },
                objectMapper);

        dispatcher.dispatch(mutation);
    }

    /**
     * Handles step failure with retry logic.
     * Retries up to maxRetries times with exponential backoff before triggering
     * compensation.
     */
    private void handleStepFailure(LaminarSaga<T> saga, SagaState state, Throwable cause,
            CompletableFuture<Void> completionFuture) {

        int currentRetry = state.retryCount();
        int stepIndex = state.currentStepIndex() + 1;

        List<SagaStep<T>> steps = saga.getSteps();
        boolean canRetry = stepIndex >= 0 && stepIndex < steps.size()
                && steps.get(stepIndex).isIdempotent();
        int stepMaxRetries = (stepIndex >= 0 && stepIndex < steps.size())
                ? steps.get(stepIndex).maxRetries()
                : maxRetries;
        int effectiveMaxRetries = Math.min(stepMaxRetries, maxRetries);

        if (canRetry && currentRetry < effectiveMaxRetries) {
            long backoffMs = backoffStrategy.calculateBackoff(currentRetry, retryBackoff.toMillis(), 60_000);

            log.warn("Saga {} step {} failed (attempt {}/{}), retrying in {}ms: {}",
                    saga.getSagaId(),
                    stepIndex >= 0 && stepIndex < steps.size() ? steps.get(stepIndex).getStepId() : "unknown",
                    currentRetry + 1,
                    effectiveMaxRetries,
                    backoffMs,
                    cause.getMessage());

            SagaState retryState = state.withRetryIncrement();
            stateRepository.save(retryState);

            executor.submit(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(backoffMs);
                    advanceSaga(saga, retryState, completionFuture);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    triggerCompensation(saga, state, cause, completionFuture);
                }
            });
        } else {
            if (currentRetry >= effectiveMaxRetries) {
                log.error("Saga {} step {} failed after {} retries, triggering compensation: {}",
                        saga.getSagaId(),
                        stepIndex >= 0 && stepIndex < steps.size() ? steps.get(stepIndex).getStepId() : "unknown",
                        currentRetry,
                        cause.getMessage());
            } else {
                log.error("Saga {} step {} is not idempotent, cannot retry. Triggering compensation: {}",
                        saga.getSagaId(),
                        stepIndex >= 0 && stepIndex < steps.size() ? steps.get(stepIndex).getStepId() : "unknown",
                        cause.getMessage());
            }
            triggerCompensation(saga, state, cause, completionFuture);
        }
    }

    /**
     * Triggers compensating transactions for all completed steps in reverse order.
     */
    private void triggerCompensation(LaminarSaga<T> saga, SagaState state, Throwable cause,
            CompletableFuture<Void> completionFuture) {
        
        metrics.recordCompensationTriggered();
        metrics.recordStatusChange(state.status(), SagaStatus.COMPENSATING);
        
        SagaState compensatingState = state.withStatus(SagaStatus.COMPENSATING);
        stateRepository.save(compensatingState);

        List<SagaStep<T>> steps = saga.getSteps();
        int lastCompletedIndex = state.currentStepIndex();

        java.util.List<SagaStep<T>> stepsToCompensate = new java.util.ArrayList<>();
        for (int i = lastCompletedIndex; i >= 0; i--) {
            SagaStep<T> step = steps.get(i);
            if (state.stepResults().containsKey(step.getStepId())) {
                stepsToCompensate.add(step);
            }
        }

        compensateNextStep(saga, stepsToCompensate, 0, new java.util.HashMap<>(), cause, completionFuture);
    }

    private void compensateNextStep(LaminarSaga<T> saga, List<SagaStep<T>> steps, int index,
            java.util.Map<String, Integer> retryCounts, Throwable originalCause,
            CompletableFuture<Void> completionFuture) {

        if (index >= steps.size()) {
            finalizeCompensation(saga, originalCause, completionFuture);
            return;
        }

        SagaStep<T> step = steps.get(index);
        String stepId = step.getStepId();

        var stateOpt = stateRepository.findById(saga.getSagaId());
        if (stateOpt.isPresent() && stateOpt.get().isCompensated(stepId)) {
            log.debug("Saga {} step {} already compensated, advancing", saga.getSagaId(), stepId);
            compensateNextStep(saga, steps, index + 1, retryCounts, originalCause, completionFuture);
            return;
        }

        log.info("Saga {} compensating step {}", saga.getSagaId(), stepId);

        LaminarDispatcher<T> dispatcher = registry.getDispatcher(saga.getEntityType());
        if (dispatcher == null) {
            log.error("No LaminarDispatcher found for {}, skipping compensation for step {}", saga.getEntityType(), stepId);
            compensateNextStep(saga, steps, index + 1, retryCounts, originalCause, completionFuture);
            return;
        }

        CompensationMutation<T> compensation = new CompensationMutation<>(step, saga.getSagaId(), stateRepository, objectMapper, saga.getEntityType());

        try {
            CompletableFuture<?> future = dispatcher.dispatch(compensation);
            future.whenCompleteAsync((result, error) -> {
                if (error != null) {
                    handleCompensationFailure(saga, steps, index, retryCounts, stepId, error, originalCause,
                            completionFuture);
                } else {
                    compensateNextStep(saga, steps, index + 1, retryCounts, originalCause, completionFuture);
                }
            }, executor);
        } catch (Exception e) {
            handleCompensationFailure(saga, steps, index, retryCounts, stepId, e, originalCause, completionFuture);
        }
    }

    private void handleCompensationFailure(LaminarSaga<T> saga, List<SagaStep<T>> steps, int index,
            java.util.Map<String, Integer> retryCounts, String stepId, Throwable error,
            Throwable originalCause, CompletableFuture<Void> completionFuture) {

        int currentRetry = retryCounts.getOrDefault(stepId, 0);
        int compensationMaxRetries = 3;

        if (currentRetry < compensationMaxRetries) {
            long backoffMs = backoffStrategy.calculateBackoff(currentRetry, retryBackoff.toMillis(), 30_000);

            log.warn("Saga {} step {} compensation failed (attempt {}/{}), retrying in {}ms: {}",
                    saga.getSagaId(), stepId, currentRetry + 1, compensationMaxRetries, backoffMs,
                    error.getMessage());

            retryCounts.put(stepId, currentRetry + 1);

            executor.submit(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(backoffMs);
                    compensateNextStep(saga, steps, index, retryCounts, originalCause, completionFuture);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    compensateNextStep(saga, steps, index + 1, retryCounts, originalCause, completionFuture);
                }
            });
        } else {
            log.error("Saga {} step {} compensation failed after {} retries, moving to next step",
                    saga.getSagaId(), stepId, compensationMaxRetries);
            compensateNextStep(saga, steps, index + 1, retryCounts, originalCause, completionFuture);
        }
    }

    private void finalizeCompensation(LaminarSaga<T> saga, Throwable cause, CompletableFuture<Void> completionFuture) {
        var stateOpt = stateRepository.findById(saga.getSagaId());
        SagaStatus oldStatus = SagaStatus.COMPENSATING;
        if (stateOpt.isPresent()) {
            oldStatus = stateOpt.get().status();
            SagaState finalState = stateOpt.get().withFailure(cause.getMessage());
            stateRepository.save(finalState);
        }

        try {
            saga.onFailure(cause);
        } catch (Exception e) {
            log.warn("onFailure callback failed", e);
        }

        if (completionFuture != null) {
            completionFuture.completeExceptionally(cause);
        }
        metrics.recordFailedSaga();
        metrics.recordStatusChange(oldStatus, SagaStatus.FAILED);
    }

    private void completeSuccess(LaminarSaga<T> saga, SagaState state, CompletableFuture<Void> completionFuture) {
        metrics.recordStatusChange(state.status(), SagaStatus.COMPLETED);
        
        state = state.withStatus(SagaStatus.COMPLETED);
        stateRepository.save(state);

        try {
            saga.onSuccess();
        } catch (Exception e) {
            log.warn("onSuccess callback failed", e);
        }

        stateRepository.delete(saga.getSagaId());
        if (completionFuture != null) {
            completionFuture.complete(null);
        }
        metrics.recordCompletedSaga();
    }

    private void startRecovery() {
        Thread.ofVirtual().name("laminar-saga-recovery").start(() -> {
            log.info("Started Saga recovery loop with interval {}", recoveryConfig.getInterval());
            while (!executor.isShutdown()) {
                try {
                    if (recoveryLock.acquireLock("recovery-leader", recoveryConfig.getInterval().toMillis())) {
                         try {
                            recoverSagas();
                         } finally {
                             // Lock will expire automatically or be renewed next cycle
                         }
                    } else {
                        log.debug("Skipping recovery, lock held by another instance");
                    }
                    Thread.sleep(recoveryConfig.getInterval().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in saga recovery loop", e);
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void recoverSagas() {
        List<SagaState> incomplete = stateRepository.findIncomplete();
        metrics.recordRecoveredSagas(incomplete.size());
        for (SagaState state : incomplete) {
            boolean timedOut = state.createdAt().plus(sagaTimeout).isBefore(java.time.Instant.now());
            
            if (!timedOut && state.updatedAt().plus(recoveryConfig.getInterval()).isAfter(java.time.Instant.now())) {
                continue;
            }

            log.info("Recovering saga {} (timedOut={})", state.sagaId(), timedOut);
            try {
                Class<?> sagaClass = Class.forName(state.sagaType());
                LaminarSaga<T> saga = (LaminarSaga<T>) objectMapper.readValue(state.serializedSaga(), sagaClass);

                CompletableFuture<Void> future = new CompletableFuture<>();
                
                if (timedOut && state.status() == SagaStatus.RUNNING) {
                    log.warn("Saga {} timed out during recovery", state.sagaId());
                    metrics.recordTimedOutSaga();
                    triggerCompensation(saga, state, new java.util.concurrent.TimeoutException("Saga timed out"), future);
                } else if (state.status() == SagaStatus.COMPENSATING) {
                     triggerCompensation(saga, state, new RuntimeException(state.failureReason()), future);
                } else {
                     advanceSaga(saga, state, future);
                }

            } catch (Exception e) {
                log.error("Failed to recover saga {}", state.sagaId(), e);
            }
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}