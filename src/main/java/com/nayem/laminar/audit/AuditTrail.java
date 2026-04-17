package com.nayem.laminar.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Audit trail for tracking mutation operations in Laminar.
 * <p>
 * Provides immutable logging of all mutation events for compliance, debugging,
 * and forensic analysis. Events are stored in-memory with optional export to 
 * external systems (e.g., Elasticsearch, database, SIEM).
 * </p>
 * 
 * <h2>Event Types</h2>
 * <ul>
 *   <li>{@code MUTATION_DISPATCHED} - Mutation submitted to Laminar</li>
 *   <li>{@code MUTATION_COALESCED} - Mutation merged with another</li>
 *   <li>{@code MUTATION_APPLIED} - Mutation successfully applied to entity</li>
 *   <li>{@code MUTATION_FAILED} - Mutation failed after retries</li>
 *   <li>{@code SAGA_STARTED} - Saga orchestration initiated</li>
 *   <li>{@code SAGA_STEP_COMPLETED} - Individual saga step completed</li>
 *   <li>{@code SAGA_COMPENSATED} - Compensation executed for failed saga</li>
 *   <li>{@code SAGA_COMPLETED} - Entire saga completed successfully</li>
 *   <li>{@code SAGA_FAILED} - Saga failed after all retries and compensations</li>
 * </ul>
 * 
 * <h2>Compliance Features</h2>
 * <ul>
 *   <li>Immutable event records (append-only)</li>
 *   <li>Timestamps in UTC</li>
 *   <li>Correlation IDs for tracing request flows</li>
 *   <li>User/context identification</li>
 *   <li>Before/after state snapshots (optional)</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * AuditTrail audit = new AuditTrail();
 * 
 * audit.logMutationDispatched(
 *     "user-123",
 *     "AddXpMutation",
 *     Map.of("xp", 100, "reason", "quest_complete"),
 *     "system",
 *     "correlation-id-456"
 * );
 * }</pre>
 * 
 * <h2>Integration</h2>
 * <p>
 * For production use, implement {@link AuditExporter} to forward events to:
 * </p>
 * <ul>
 *   <li>Elasticsearch for search and analytics</li>
 *   <li>Kafka for streaming to SIEM systems</li>
 *   <li>Database for long-term retention</li>
 *   <li>CloudWatch Logs / Stackdriver for cloud-native logging</li>
 * </ul>
 */
public class AuditTrail {
    
    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class);
    
    private final AtomicLong sequenceGenerator = new AtomicLong(0);
    private final Map<String, AuditEvent> recentEvents = new ConcurrentHashMap<>();
    private final int maxInMemoryEvents;
    
    /**
     * Creates a new audit trail with default capacity.
     */
    public AuditTrail() {
        this(10000);
    }
    
    /**
     * Creates a new audit trail.
     *
     * @param maxInMemoryEvents maximum events to retain in memory
     */
    public AuditTrail(int maxInMemoryEvents) {
        this.maxInMemoryEvents = maxInMemoryEvents;
    }
    
    /**
     * Logs a mutation dispatch event.
     *
     * @param entityKey the entity being modified
     * @param mutationType the type of mutation
     * @param payload the mutation data
     * @param userId the user initiating the operation
     * @param correlationId correlation ID for tracing
     * @return the created audit event
     */
    public AuditEvent logMutationDispatched(String entityKey, String mutationType, 
                                             Map<String, Object> payload,
                                             String userId, String correlationId) {
        return createEvent(EventType.MUTATION_DISPATCHED)
            .entityKey(entityKey)
            .mutationType(mutationType)
            .payload(payload)
            .userId(userId)
            .correlationId(correlationId)
            .buildAndStore();
    }
    
    /**
     * Logs a mutation coalesced event.
     *
     * @param entityKey the entity being modified
     * @param originalMutationType the original mutation type
     * @param coalescedMutationType the resulting coalesced mutation type
     * @param coalescedCount number of mutations merged
     * @param correlationId correlation ID for tracing
     * @return the created audit event
     */
    public AuditEvent logMutationCoalesced(String entityKey, String originalMutationType,
                                            String coalescedMutationType, int coalescedCount,
                                            String correlationId) {
        return createEvent(EventType.MUTATION_COALESCED)
            .entityKey(entityKey)
            .mutationType(originalMutationType)
            .detail("coalescedType", coalescedMutationType)
            .detail("coalescedCount", coalescedCount)
            .correlationId(correlationId)
            .buildAndStore();
    }
    
    /**
     * Logs a mutation applied event.
     *
     * @param entityKey the entity that was modified
     * @param mutationType the mutation that was applied
     * @param beforeState entity state before mutation (optional)
     * @param afterState entity state after mutation (optional)
     * @param correlationId correlation ID for tracing
     * @return the created audit event
     */
    public AuditEvent logMutationApplied(String entityKey, String mutationType,
                                          Object beforeState, Object afterState,
                                          String correlationId) {
        return createEvent(EventType.MUTATION_APPLIED)
            .entityKey(entityKey)
            .mutationType(mutationType)
            .beforeState(beforeState)
            .afterState(afterState)
            .correlationId(correlationId)
            .buildAndStore();
    }
    
    /**
     * Logs a mutation failure event.
     *
     * @param entityKey the entity that failed to update
     * @param mutationType the mutation that failed
     * @param exceptionMessage error message
     * @param retryCount number of retry attempts
     * @param correlationId correlation ID for tracing
     * @return the created audit event
     */
    public AuditEvent logMutationFailed(String entityKey, String mutationType,
                                         String exceptionMessage, int retryCount,
                                         String correlationId) {
        return createEvent(EventType.MUTATION_FAILED)
            .entityKey(entityKey)
            .mutationType(mutationType)
            .detail("exceptionMessage", exceptionMessage)
            .detail("retryCount", retryCount)
            .correlationId(correlationId)
            .buildAndStore();
    }
    
    /**
     * Logs a saga started event.
     *
     * @param sagaId unique saga identifier
     * @param sagaType the type of saga
     * @param description human-readable description
     * @param userId the user initiating the saga
     * @param correlationId correlation ID for tracing
     * @return the created audit event
     */
    public AuditEvent logSagaStarted(String sagaId, String sagaType, String description,
                                      String userId, String correlationId) {
        return createEvent(EventType.SAGA_STARTED)
            .sagaId(sagaId)
            .sagaType(sagaType)
            .detail("description", description)
            .userId(userId)
            .correlationId(correlationId)
            .buildAndStore();
    }
    
    /**
     * Logs a saga step completed event.
     *
     * @param sagaId the saga identifier
     * @param stepId the step identifier
     * @param entityKey the entity affected by this step
     * @param correlationId correlation ID for tracing
     * @return the created audit event
     */
    public AuditEvent logSagaStepCompleted(String sagaId, String stepId, String entityKey,
                                            String correlationId) {
        return createEvent(EventType.SAGA_STEP_COMPLETED)
            .sagaId(sagaId)
            .detail("stepId", stepId)
            .entityKey(entityKey)
            .correlationId(correlationId)
            .buildAndStore();
    }
    
    /**
     * Logs a saga compensation event.
     *
     * @param sagaId the saga identifier
     * @param stepId the step being compensated
     * @param reason reason for compensation
     * @param correlationId correlation ID for tracing
     * @return the created audit event
     */
    public AuditEvent logSagaCompensated(String sagaId, String stepId, String reason,
                                          String correlationId) {
        return createEvent(EventType.SAGA_COMPENSATED)
            .sagaId(sagaId)
            .detail("stepId", stepId)
            .detail("reason", reason)
            .correlationId(correlationId)
            .buildAndStore();
    }
    
    /**
     * Logs a saga completion event.
     *
     * @param sagaId the saga identifier
     * @param sagaType the type of saga
     * @param stepsCompleted number of steps completed
     * @param correlationId correlation ID for tracing
     * @return the created audit event
     */
    public AuditEvent logSagaCompleted(String sagaId, String sagaType, int stepsCompleted,
                                        String correlationId) {
        return createEvent(EventType.SAGA_COMPLETED)
            .sagaId(sagaId)
            .sagaType(sagaType)
            .detail("stepsCompleted", stepsCompleted)
            .correlationId(correlationId)
            .buildAndStore();
    }
    
    /**
     * Logs a saga failure event.
     *
     * @param sagaId the saga identifier
     * @param sagaType the type of saga
     * @param failedStepId the step that failed
     * @param reason failure reason
     * @param correlationId correlation ID for tracing
     * @return the created audit event
     */
    public AuditEvent logSagaFailed(String sagaId, String sagaType, String failedStepId,
                                     String reason, String correlationId) {
        return createEvent(EventType.SAGA_FAILED)
            .sagaId(sagaId)
            .sagaType(sagaType)
            .detail("failedStepId", failedStepId)
            .detail("reason", reason)
            .correlationId(correlationId)
            .buildAndStore();
    }
    
    /**
     * Gets recent audit events.
     *
     * @param limit maximum number of events to return
     * @return list of recent events
     */
    public java.util.List<AuditEvent> getRecentEvents(int limit) {
        return recentEvents.values().stream()
            .sorted((a, b) -> Long.compare(b.sequence(), a.sequence()))
            .limit(limit)
            .toList();
    }
    
    /**
     * Gets events by entity key.
     *
     * @param entityKey the entity key to filter by
     * @param limit maximum number of events to return
     * @return list of events for the entity
     */
    public java.util.List<AuditEvent> getEventsByEntity(String entityKey, int limit) {
        return recentEvents.values().stream()
            .filter(e -> entityKey.equals(e.entityKey()))
            .sorted((a, b) -> Long.compare(b.sequence(), a.sequence()))
            .limit(limit)
            .toList();
    }
    
    /**
     * Gets events by saga ID.
     *
     * @param sagaId the saga ID to filter by
     * @param limit maximum number of events to return
     * @return list of events for the saga
     */
    public java.util.List<AuditEvent> getEventsBySaga(String sagaId, int limit) {
        return recentEvents.values().stream()
            .filter(e -> sagaId.equals(e.sagaId()))
            .sorted((a, b) -> Long.compare(b.sequence(), a.sequence()))
            .limit(limit)
            .toList();
    }
    
    /**
     * Clears all audit events from memory.
     * <p>
     * Use with caution - only appropriate after exporting to persistent storage.
     * </p>
     */
    public void clear() {
        recentEvents.clear();
    }
    
    private AuditEventBuilder createEvent(EventType type) {
        return new AuditEventBuilder(type);
    }
    
    private void storeEvent(AuditEvent event) {
        if (recentEvents.size() >= maxInMemoryEvents) {
            // Remove oldest event (simple eviction - could be improved with priority queue)
            String oldestKey = recentEvents.entrySet().stream()
                .min(Map.Entry.comparingByValue((a, b) -> 
                    Long.compare(a.sequence(), b.sequence())))
                .map(Map.Entry::getKey)
                .orElse(null);
            if (oldestKey != null) {
                recentEvents.remove(oldestKey);
            }
        }
        recentEvents.put(event.id(), event);
        log.debug("Audit event: {} - {} for entity {}", 
            event.type(), event.id(), event.entityKey());
    }
    
    /**
     * Event types supported by the audit trail.
     */
    public enum EventType {
        MUTATION_DISPATCHED,
        MUTATION_COALESCED,
        MUTATION_APPLIED,
        MUTATION_FAILED,
        SAGA_STARTED,
        SAGA_STEP_COMPLETED,
        SAGA_COMPENSATED,
        SAGA_COMPLETED,
        SAGA_FAILED
    }
    
    /**
     * Immutable audit event record.
     *
     * @param id unique event identifier
     * @param sequence monotonically increasing sequence number
     * @param timestamp event timestamp in UTC
     * @param type event type
     * @param entityKey the entity involved (if applicable)
     * @param sagaId the saga involved (if applicable)
     * @param sagaType the saga type (if applicable)
     * @param mutationType the mutation type (if applicable)
     * @param userId the user who initiated the operation
     * @param correlationId correlation ID for distributed tracing
     * @param payload mutation payload data
     * @param details additional event-specific details
     * @param beforeState entity state before mutation
     * @param afterState entity state after mutation
     */
    public record AuditEvent(
        String id,
        long sequence,
        Instant timestamp,
        EventType type,
        String entityKey,
        String sagaId,
        String sagaType,
        String mutationType,
        String userId,
        String correlationId,
        Map<String, Object> payload,
        Map<String, Object> details,
        Object beforeState,
        Object afterState
    ) {}
    
    /**
     * Builder for constructing audit events.
     */
    private class AuditEventBuilder {
        private final EventType type;
        private String entityKey;
        private String sagaId;
        private String sagaType;
        private String mutationType;
        private String userId;
        private String correlationId;
        private Map<String, Object> payload = Map.of();
        private Map<String, Object> details = Map.of();
        private Object beforeState;
        private Object afterState;
        
        AuditEventBuilder(EventType type) {
            this.type = type;
        }
        
        AuditEventBuilder entityKey(String entityKey) {
            this.entityKey = entityKey;
            return this;
        }
        
        AuditEventBuilder sagaId(String sagaId) {
            this.sagaId = sagaId;
            return this;
        }
        
        AuditEventBuilder sagaType(String sagaType) {
            this.sagaType = sagaType;
            return this;
        }
        
        AuditEventBuilder mutationType(String mutationType) {
            this.mutationType = mutationType;
            return this;
        }
        
        AuditEventBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }
        
        AuditEventBuilder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }
        
        AuditEventBuilder payload(Map<String, Object> payload) {
            this.payload = Map.copyOf(payload);
            return this;
        }
        
        AuditEventBuilder detail(String key, Object value) {
            var newDetails = new java.util.HashMap<>(details);
            newDetails.put(key, value);
            this.details = Map.copyOf(newDetails);
            return this;
        }
        
        AuditEventBuilder beforeState(Object beforeState) {
            this.beforeState = beforeState;
            return this;
        }
        
        AuditEventBuilder afterState(Object afterState) {
            this.afterState = afterState;
            return this;
        }
        
        AuditEvent buildAndStore() {
            String eventId = type.name() + "-" + System.currentTimeMillis() + "-" + sequenceGenerator.incrementAndGet();
            AuditEvent event = new AuditEvent(
                eventId,
                sequenceGenerator.get(),
                Instant.now(),
                type,
                entityKey,
                sagaId,
                sagaType,
                mutationType,
                userId,
                correlationId,
                payload,
                details,
                beforeState,
                afterState
            );
            storeEvent(event);
            return event;
        }
    }
}
