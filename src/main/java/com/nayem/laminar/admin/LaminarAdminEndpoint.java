package com.nayem.laminar.admin;

import com.nayem.laminar.dlq.DeadLetterQueue;
import com.nayem.laminar.spring.LaminarProperties;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring Boot Actuator endpoint for Laminar management operations.
 */
@Component
@Endpoint(id = "laminar")
public class LaminarAdminEndpoint {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private final DeadLetterQueue<?> deadLetterQueue;
    private final LaminarProperties properties;

    public LaminarAdminEndpoint(Optional<DeadLetterQueue<?>> deadLetterQueue,
                                LaminarProperties properties) {
        this.deadLetterQueue = deadLetterQueue.orElse(null);
        this.properties = properties;
    }

    @ReadOperation
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        Map<String, Object> config = new HashMap<>();
        config.put("maxWaiters", properties.getMaxWaiters());
        config.put("timeout", properties.getTimeout().toString());
        config.put("maxBatchSize", properties.getMaxBatchSize());
        config.put("clusterEnabled", properties.getCluster().isEnabled());
        config.put("sagaEnabled", properties.getSaga().isEnabled());
        config.put("dlqEnabled", properties.getDlq().isEnabled());
        stats.put("configuration", config);

        if (deadLetterQueue != null) {
            stats.put("deadLetterQueue", Map.of(
                "enabled", true,
                "size", deadLetterQueue.size()
            ));
        } else {
            stats.put("deadLetterQueue", Map.of("enabled", false));
        }
        return stats;
    }

    @ReadOperation
    public Map<String, Object> readSection(@Selector String section, Integer limit, Integer offset) {
        return switch (section) {
            case "dlq" -> listDlqEntries(limit, offset);
            case "workers" -> listWorkers();
            case "health" -> getHealth();
            default -> Map.of(
                "success", false,
                "error", "Unknown section: " + section
            );
        };
    }

    @WriteOperation
    public Map<String, Object> writeSection(@Selector String section, @Selector String action, String id) {
        if ("dlq".equals(section) && "replay".equals(action)) {
            return replayDlqEntry(id);
        }
        if ("workers".equals(section) && "evict".equals(action)) {
            return evictWorker(id);
        }
        return Map.of(
            "success", false,
            "error", "Unknown action: " + section + "/" + action
        );
    }

    @DeleteOperation
    public Map<String, Object> deleteSection(@Selector String section, @Selector String id) {
        if (!"dlq".equals(section)) {
            return Map.of(
                "success", false,
                "error", "Delete is only supported for the 'dlq' section"
            );
        }
        if (deadLetterQueue == null) {
            return Map.of(
                "success", false,
                "error", "DLQ is not enabled"
            );
        }
        deadLetterQueue.acknowledge(id);
        return Map.of(
            "success", true,
            "entryId", id
        );
    }

    private Map<String, Object> listDlqEntries(Integer limit, Integer offset) {
        int lim = normalizeLimit(limit);
        int off = Math.max(offset == null ? 0 : offset, 0);
        Map<String, Object> result = new HashMap<>();

        if (deadLetterQueue == null) {
            result.put("enabled", false);
            result.put("entries", List.of());
            return result;
        }

        int fetchCount = Math.min(MAX_LIMIT, lim + off);
        var entries = deadLetterQueue.list(fetchCount);

        result.put("enabled", true);
        result.put("total", deadLetterQueue.size());
        result.put("limit", lim);
        result.put("offset", off);
        result.put("entries", entries.stream()
            .skip(off)
            .limit(lim)
            .map(this::toDlqEntryView)
            .toList());
        return result;
    }

    private Map<String, Object> replayDlqEntry(String id) {
        if (deadLetterQueue == null) {
            return Map.of(
                "success", false,
                "error", "DLQ is not enabled"
            );
        }
        if (id == null || id.isBlank()) {
            return Map.of(
                "success", false,
                "error", "entry id is required"
            );
        }

        int fetchCount = (int) Math.min(MAX_LIMIT, Math.max(deadLetterQueue.size(), DEFAULT_LIMIT));
        var entry = deadLetterQueue.list(fetchCount).stream()
            .filter(e -> id.equals(e.id()))
            .findFirst();

        if (entry.isEmpty()) {
            return Map.of(
                "success", false,
                "error", "DLQ entry not found",
                "entryId", id
            );
        }

        var replayedEntry = entry.get().withIncrementedRetry();
        deadLetterQueue.send(replayedEntry);
        deadLetterQueue.acknowledge(id);
        return Map.of(
            "success", true,
            "entryId", id,
            "newRetryCount", replayedEntry.retryCount()
        );
    }

    private Map<String, Object> listWorkers() {
        return Map.of(
            "workers", List.of(),
            "totalActive", 0,
            "note", "Worker statistics collection pending implementation"
        );
    }

    private Map<String, Object> evictWorker(String entityKey) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", "Worker eviction not yet implemented");
        response.put("entityKey", entityKey);
        return response;
    }

    private Map<String, Object> getHealth() {
        Map<String, Object> health = new HashMap<>();
        boolean healthy = true;
        List<String> issues = new java.util.ArrayList<>();

        if (deadLetterQueue != null && deadLetterQueue.size() > 100) {
            issues.add("High DLQ count: " + deadLetterQueue.size());
            healthy = false;
        }

        health.put("status", healthy ? "UP" : "DEGRADED");
        health.put("issues", issues);
        health.put("timestamp", Instant.now().toString());
        return health;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private Map<String, Object> toDlqEntryView(DeadLetterQueue.DlqEntry<?> entry) {
        return Map.of(
            "id", entry.id(),
            "entityKey", entry.entityKey(),
            "errorMessage", entry.errorMessage(),
            "errorClass", entry.errorClass(),
            "retryCount", entry.retryCount(),
            "timestamp", entry.timestamp(),
            "lastRetryTimestamp", entry.lastRetryTimestamp()
        );
    }
}
