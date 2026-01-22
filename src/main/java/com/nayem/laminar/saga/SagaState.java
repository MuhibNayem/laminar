package com.nayem.laminar.saga;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Persistent state of a Saga execution.
 * <p>
 * This record is persisted to enable crash recovery. If the application
 * crashes mid-saga, on restart the SagaOrchestrator can resume or
 * compensate based on this state.
 * </p>
 *
 * @param sagaId              Unique identifier for this saga
 * @param sagaType            Class name of the saga for reconstruction
 * @param serializedSaga      JSON serialized saga data for recovery
 * @param status              Current execution status
 * @param currentStepIndex    Index of the step currently being executed (or
 *                            last completed)
 * @param retryCount          Number of retry attempts for the current step
 * @param stepResults         Serialized results of each step (for compensation
 *                            context)
 * @param compensatedSteps    Set of step IDs that have been successfully
 *                            compensated
 * @param failedCompensations Map of step ID to failure reason for failed
 *                            compensations
 * @param createdAt           When the saga was created
 * @param updatedAt           When the saga state was last updated
 * @param failureReason       Reason for failure (if status is FAILED)
 */
public record SagaState(
        String sagaId,
        String sagaType,
        String serializedSaga,
        SagaStatus status,
        int currentStepIndex,
        int retryCount,
        Map<String, String> stepResults,
        Set<String> compensatedSteps,
        Map<String, String> failedCompensations,
        Instant createdAt,
        Instant updatedAt,
        String failureReason) {

    public static SagaState initial(String sagaId, String sagaType, String serializedSaga) {
        Instant now = Instant.now();
        return new SagaState(
                sagaId,
                sagaType,
                serializedSaga,
                SagaStatus.PENDING,
                -1,
                0,
                Map.of(),
                Set.of(),
                Map.of(),
                now,
                now,
                null);
    }

    public SagaState withStatus(SagaStatus newStatus) {
        return new SagaState(
                sagaId, sagaType, serializedSaga, newStatus, currentStepIndex, retryCount,
                stepResults, compensatedSteps, failedCompensations,
                createdAt, Instant.now(), failureReason);
    }

    public SagaState withStepIndex(int newIndex) {
        return new SagaState(
                sagaId, sagaType, serializedSaga, status, newIndex, 0,
                stepResults, compensatedSteps, failedCompensations,
                createdAt, Instant.now(), failureReason);
    }

    public SagaState withRetryIncrement() {
        return new SagaState(
                sagaId, sagaType, serializedSaga, status, currentStepIndex, retryCount + 1,
                stepResults, compensatedSteps, failedCompensations,
                createdAt, Instant.now(), failureReason);
    }

    public SagaState withFailure(String reason) {
        return new SagaState(
                sagaId, sagaType, serializedSaga, SagaStatus.FAILED, currentStepIndex, retryCount,
                stepResults, compensatedSteps, failedCompensations,
                createdAt, Instant.now(), reason);
    }

    public SagaState withStepResult(String stepId, String serializedResult) {
        var newResults = new java.util.HashMap<>(stepResults);
        newResults.put(stepId, serializedResult);
        return new SagaState(
                sagaId, sagaType, serializedSaga, status, currentStepIndex, retryCount,
                Map.copyOf(newResults), compensatedSteps, failedCompensations,
                createdAt, Instant.now(), failureReason);
    }

    public SagaState withCompensatedStep(String stepId) {
        var newCompensated = new java.util.HashSet<>(compensatedSteps);
        newCompensated.add(stepId);
        return new SagaState(
                sagaId, sagaType, serializedSaga, status, currentStepIndex, retryCount,
                stepResults, Set.copyOf(newCompensated), failedCompensations,
                createdAt, Instant.now(), failureReason);
    }

    public boolean isCompensated(String stepId) {
        return compensatedSteps.contains(stepId);
    }

    public SagaState withFailedCompensation(String stepId, String reason) {
        var newFailed = new java.util.HashMap<>(failedCompensations);
        newFailed.put(stepId, reason);
        return new SagaState(
                sagaId, sagaType, serializedSaga, status, currentStepIndex, retryCount,
                stepResults, compensatedSteps, Map.copyOf(newFailed),
                createdAt, Instant.now(), failureReason);
    }
}