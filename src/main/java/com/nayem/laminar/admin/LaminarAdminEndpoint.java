package com.nayem.laminar.admin;

import com.nayem.laminar.dlq.DeadLetterQueue;
import com.nayem.laminar.spring.LaminarProperties;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring Boot Actuator endpoint for Laminar management operations.
 * <p>
 * Provides HTTP endpoints for monitoring and managing the Laminar engine:
 * </p>
 * <ul>
 *   <li>{@code GET /actuator/laminar} - Get engine statistics</li>
 *   <li>{@code GET /actuator/laminar/dlq} - List dead letter queue entries</li>
 *   <li>{@code POST /actuator/laminar/dlq/{id}/replay} - Replay a DLQ entry</li>
 *   <li>{@code DELETE /actuator/laminar/dlq/{id}} - Remove a DLQ entry</li>
 *   <li>{@code GET /actuator/laminar/workers} - List active entity workers</li>
 *   <li>{@code POST /actuator/laminar/workers/{key}/evict} - Evict a specific worker</li>
 * </ul>
 * 
 * <h2>Security</h2>
 * <p>
 * This endpoint should be secured in production. Example configuration:
 * </p>
 * <pre>{@code
 * management:
 *   endpoint:
 *     laminar:
 *       access: roles('ADMIN', 'OPERATOR')
 * }</pre>
 * 
 * @see org.springframework.boot.actuate.endpoint.annotation.Endpoint
 */
@Component
@Endpoint(id = "laminar")
public class LaminarAdminEndpoint {

    private final DeadLetterQueue deadLetterQueue;
    private final LaminarProperties properties;
    // TODO: Inject LaminarRegistry or ClusterWorkerManager for worker stats
    
    /**
     * Constructs a new Laminar admin endpoint.
     *
     * @param deadLetterQueue the dead letter queue (may be null if disabled)
     * @param properties the Laminar configuration properties
     */
    public LaminarAdminEndpoint(Optional<DeadLetterQueue> deadLetterQueue, 
                                 LaminarProperties properties) {
        this.deadLetterQueue = deadLetterQueue.orElse(null);
        this.properties = properties;
    }

    /**
     * Returns comprehensive statistics about the Laminar engine.
     *
     * @return map containing engine metrics and configuration
     */
    @ReadOperation
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        
        // Configuration
        Map<String, Object> config = new HashMap<>();
        config.put("maxWaiters", properties.getMaxWaiters());
        config.put("timeout", properties.getTimeout().toString());
        config.put("maxBatchSize", properties.getMaxBatchSize());
        config.put("clusterEnabled", properties.getCluster().isEnabled());
        config.put("sagaEnabled", properties.getSaga().isEnabled());
        config.put("dlqEnabled", properties.getDlq().isEnabled());
        stats.put("configuration", config);
        
        // DLQ Stats (if enabled)
        if (deadLetterQueue != null) {
            Map<String, Object> dlqStats = new HashMap<>();
            dlqStats.put("size", deadLetterQueue.size());
            dlqStats.put("enabled", true);
            stats.put("deadLetterQueue", dlqStats);
        } else {
            Map<String, Object> dlqStats = new HashMap<>();
            dlqStats.put("enabled", false);
            stats.put("deadLetterQueue", dlqStats);
        }
        
        // TODO: Add worker stats, batch processing rates, coalescing efficiency
        // These require injection of LaminarRegistry or internal metrics
        
        return stats;
    }

    /**
     * Lists entries in the Dead Letter Queue.
     *
     * @param limit maximum number of entries to return (default: 100)
     * @param offset pagination offset (default: 0)
     * @return list of DLQ entries with metadata
     */
    @ReadOperation(selector = "dlq")
    public Map<String, Object> listDlqEntries(
            @Selector Optional<Integer> limit,
            @Selector Optional<Integer> offset) {
        
        int lim = limit.orElse(100);
        int off = offset.orElse(0);
        
        Map<String, Object> result = new HashMap<>();
        
        if (deadLetterQueue == null) {
            result.put("enabled", false);
            result.put("entries", List.of());
            return result;
        }
        
        // TODO: Implement pagination when DLQ supports it
        // For now, return all entries (DLQ is typically small)
        var entries = deadLetterQueue.poll(Math.max(lim, 1000));
        
        // Re-queue entries after reading (read-only operation)
        entries.forEach(entry -> deadLetterQueue.send(
            entry.entityKey(),
            entry.mutation(),
            entry.exceptionMessage(),
            entry.stackTrace()
        ));
        
        result.put("enabled", true);
        result.put("total", deadLetterQueue.size());
        result.put("limit", lim);
        result.put("offset", off);
        result.put("entries", entries.stream()
            .limit(lim)
            .skip(off)
            .map(e -> Map.of(
                "entityKey", e.entityKey(),
                "exceptionMessage", e.exceptionMessage(),
                "timestamp", e.timestamp(),
                "retryCount", e.retryCount()
            ))
            .toList());
        
        return result;
    }

    /**
     * Replays a specific DLQ entry, re-queuing it for processing.
     * <p>
     * Note: This is a placeholder - actual replay requires mutation reconstruction
     * and integration with the Laminar engine's dispatch mechanism.
     * </p>
     *
     * @param id the DLQ entry identifier
     * @return operation result
     */
    @WriteOperation(selector = "dlq/replay")
    public Map<String, Object> replayDlqEntry(@Selector String id) {
        Map<String, Object> result = new HashMap<>();
        
        if (deadLetterQueue == null) {
            result.put("success", false);
            result.put("error", "DLQ is not enabled");
            return result;
        }
        
        // TODO: Implement actual replay logic
        // This requires:
        // 1. Fetching the specific entry by ID
        // 2. Deserializing the mutation
        // 3. Re-dispatching through LaminarEngine
        // 4. Removing from DLQ on success
        
        result.put("success", false);
        result.put("error", "Replay not yet implemented - manual intervention required");
        result.put("entryId", id);
        
        return result;
    }

    /**
     * Removes a specific entry from the Dead Letter Queue.
     *
     * @param id the DLQ entry identifier
     * @return operation result
     */
    @DeleteOperation(selector = "dlq")
    public Map<String, Object> deleteDlqEntry(@Selector String id) {
        Map<String, Object> result = new HashMap<>();
        
        if (deadLetterQueue == null) {
            result.put("success", false);
            result.put("error", "DLQ is not enabled");
            return result;
        }
        
        // TODO: Implement targeted deletion
        // Current DLQ API only supports poll (FIFO), not random access deletion
        
        result.put("success", false);
        result.put("error", "Targeted deletion not yet implemented - use poll to clear DLQ");
        result.put("entryId", id);
        
        return result;
    }

    /**
     * Lists active entity workers and their statistics.
     *
     * @return map of worker statistics
     */
    @ReadOperation(selector = "workers")
    public Map<String, Object> listWorkers() {
        Map<String, Object> result = new HashMap<>();
        
        // TODO: Integrate with LaminarRegistry/ClusterWorkerManager
        // to provide real-time worker statistics:
        // - Active workers count
        // - Queue depth per worker
        // - Processing rates
        // - Last activity timestamp
        
        result.put("workers", List.of());
        result.put("totalActive", 0);
        result.put("note", "Worker statistics collection pending implementation");
        
        return result;
    }

    /**
     * Forces eviction of a specific entity worker from the cache.
     * <p>
     * Useful for debugging or forcing re-initialization of a problematic worker.
     * </p>
     *
     * @param entityKey the entity key identifying the worker
     * @return operation result
     */
    @WriteOperation(selector = "workers/evict")
    public Map<String, Object> evictWorker(@Selector String entityKey) {
        Map<String, Object> result = new HashMap<>();
        
        // TODO: Integrate with LaminarRegistry to evict specific worker
        // Must ensure:
        // 1. No pending mutations for this worker
        // 2. Graceful shutdown of worker thread
        // 3. Removal from cache
        
        result.put("success", false);
        result.put("error", "Worker eviction not yet implemented");
        result.put("entityKey", entityKey);
        
        return result;
    }

    /**
     * Returns detailed health information for the Laminar engine.
     *
     * @return health status with details
     */
    @ReadOperation(selector = "health")
    public Map<String, Object> getHealth() {
        Map<String, Object> health = new HashMap<>();
        
        boolean healthy = true;
        List<String> issues = new java.util.ArrayList<>();
        
        // Check DLQ size (warning if too many failed mutations)
        if (deadLetterQueue != null && deadLetterQueue.size() > 100) {
            issues.add("High DLQ count: " + deadLetterQueue.size());
            healthy = false;
        }
        
        // TODO: Add more health checks:
        // - Worker thread liveness
        // - Redis connectivity (if clustered)
        // - Saga recovery lock status
        // - Circuit breaker state
        
        health.put("status", healthy ? "UP" : "DEGRADED");
        health.put("issues", issues);
        health.put("timestamp", java.time.Instant.now().toString());
        
        return health;
    }
}
