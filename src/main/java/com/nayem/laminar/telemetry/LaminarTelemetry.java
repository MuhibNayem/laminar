package com.nayem.laminar.telemetry;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Metrics telemetry integration for Laminar.
 * <p>
 * Provides metrics and logging integration using Micrometer
 * for backends such as Prometheus and OpenTelemetry bridges.
 * </p>
 * 
 * <h2>Features</h2>
 * <ul>
 *   <li>Metrics for batch processing, coalescing efficiency, and worker performance</li>
 *   <li>Custom tags for entity types, operations, and status</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * LaminarTelemetry telemetry = new LaminarTelemetry(meterRegistry);
 * 
 * // Record mutation processing
 * telemetry.recordMutation("user", "add_xp", Duration.ofMillis(50), true);
 * 
 * }</pre>
 * 
 * <h2>Integration with OpenTelemetry</h2>
 * <p>
 * To use with OpenTelemetry, configure Micrometer to use the OpenTelemetry registry:
 * </p>
 * <pre>{@code
 * // Add dependency: io.opentelemetry:opentelemetry-exporter-otlp
 * MeterRegistry registry = OpenTelemetryMeterRegistry.builder(openTelemetry).build();
 * LaminarTelemetry telemetry = new LaminarTelemetry(registry);
 * }</pre>
 */
public class LaminarTelemetry {
    
    private static final Logger log = LoggerFactory.getLogger(LaminarTelemetry.class);
    
    private final MeterRegistry meterRegistry;
    private final Map<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<Double>> coalescingRatioGaugeCache = new ConcurrentHashMap<>();
    private final boolean enabled;
    
    /**
     * Creates a new telemetry instance.
     *
     * @param meterRegistry the Micrometer meter registry (can be null to disable telemetry)
     */
    public LaminarTelemetry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.enabled = meterRegistry != null;
        
        if (enabled) {
            log.info("Laminar telemetry enabled with registry: {}", meterRegistry.getClass().getSimpleName());
        } else {
            log.warn("Laminar telemetry disabled - no MeterRegistry provided");
        }
    }
    
    /**
     * Records a mutation processing event.
     *
     * @param entityType the type of entity being mutated
     * @param operation the operation name
     * @param duration the processing duration
     * @param success whether the operation succeeded
     */
    public void recordMutation(String entityType, String operation, Duration duration, boolean success) {
        if (!enabled) {
            return;
        }
        
        try {
            Timer timer = getOrCreateTimer(
                "laminar.mutation.duration",
                "Duration of mutation processing",
                Tag.of("entity.type", entityType),
                Tag.of("operation", operation),
                Tag.of("status", success ? "success" : "failure")
            );
            timer.record(duration);
            
            meterRegistry.counter(
                "laminar.mutation.count",
                Tags.of("entity.type", entityType,
                        "operation", operation,
                        "status", success ? "success" : "failure")
            ).increment();
            
        } catch (Exception e) {
            log.debug("Failed to record mutation telemetry", e);
        }
    }
    
    /**
     * Records a batch processing event.
     *
     * @param entityType the type of entity
     * @param batchSize the number of mutations coalesced
     * @param duration the batch processing duration
     * @param coalescingRatio the ratio of requests coalesced (e.g., 10.0 means 10 requests became 1 write)
     */
    public void recordBatch(String entityType, int batchSize, Duration duration, double coalescingRatio) {
        if (!enabled || batchSize <= 0) {
            return;
        }
        
        try {
            Timer timer = getOrCreateTimer(
                "laminar.batch.duration",
                "Duration of batch processing",
                Tag.of("entity.type", entityType)
            );
            timer.record(duration);
            
            meterRegistry.counter(
                "laminar.batch.count",
                Tags.of("entity.type", entityType)
            ).increment();
            
            meterRegistry.summary(
                "laminar.batch.size",
                Tags.of("entity.type", entityType)
            ).record(batchSize);
            
            AtomicReference<Double> ratioRef = coalescingRatioGaugeCache.computeIfAbsent(
                entityType,
                type -> {
                    AtomicReference<Double> reference = new AtomicReference<>(0.0d);
                    Gauge.builder("laminar.coalescing.ratio", reference, AtomicReference::get)
                        .description("Observed coalescing ratio per entity type")
                        .tags(Tags.of("entity.type", type))
                        .register(meterRegistry);
                    return reference;
                }
            );
            ratioRef.set(coalescingRatio);
            
        } catch (Exception e) {
            log.debug("Failed to record batch telemetry", e);
        }
    }
    
    /**
     * Records a rate limiting event.
     *
     * @param key the rate limit key
     * @param allowed whether the request was allowed
     */
    public void recordRateLimit(String key, boolean allowed) {
        if (!enabled) {
            return;
        }
        
        try {
            meterRegistry.counter(
                "laminar.ratelimit.count",
                Tags.of("key", key, "allowed", String.valueOf(allowed))
            ).increment();
            
        } catch (Exception e) {
            log.debug("Failed to record rate limit telemetry", e);
        }
    }
    
    /**
     * Records a circuit breaker state change.
     *
     * @param serviceName the service name
     * @param fromState the previous state
     * @param toState the new state
     */
    public void recordCircuitBreakerStateChange(String serviceName, String fromState, String toState) {
        if (!enabled) {
            return;
        }
        
        try {
            meterRegistry.counter(
                "laminar.circuitbreaker.state.change",
                Tags.of("service", serviceName,
                        "from.state", fromState,
                        "to.state", toState)
            ).increment();
            
        } catch (Exception e) {
            log.debug("Failed to record circuit breaker state change", e);
        }
    }
    
    /**
     * Records a saga execution event.
     *
     * @param sagaType the type of saga
     * @param status the saga status
     * @param duration the execution duration
     * @param stepCount the number of steps in the saga
     */
    public void recordSaga(String sagaType, String status, Duration duration, int stepCount) {
        if (!enabled) {
            return;
        }
        
        try {
            Timer timer = getOrCreateTimer(
                "laminar.saga.duration",
                "Duration of saga execution",
                Tag.of("saga.type", sagaType),
                Tag.of("status", status),
                Tag.of("step.count", String.valueOf(stepCount))
            );
            timer.record(duration);
            
            meterRegistry.counter(
                "laminar.saga.count",
                Tags.of("saga.type", sagaType, "status", status)
            ).increment();
            
        } catch (Exception e) {
            log.debug("Failed to record saga telemetry", e);
        }
    }
    
    /**
     * Records a Dead Letter Queue event.
     *
     * @param entityType the entity type
     * @param action the DLQ action (send, poll, acknowledge)
     */
    public void recordDlqEvent(String entityType, String action) {
        if (!enabled) {
            return;
        }
        
        try {
            meterRegistry.counter(
                "laminar.dlq.event",
                Tags.of("entity.type", entityType, "action", action)
            ).increment();
            
        } catch (Exception e) {
            log.debug("Failed to record DLQ event", e);
        }
    }
    
    /**
     * Gets or creates a cached Timer instance.
     */
    private Timer getOrCreateTimer(String name, String description, Tag... tags) {
        String cacheKey = name + ":" + String.join(",", 
            java.util.Arrays.stream(tags)
                .map(t -> t.getKey() + "=" + t.getValue())
                .toArray(String[]::new)
        );
        
        return timerCache.computeIfAbsent(cacheKey, k -> 
            Timer.builder(name)
                .description(description)
                .tags(tags)
                .publishPercentileHistogram()
                .register(meterRegistry)
        );
    }
    
    /**
     * Checks if telemetry is enabled.
     *
     * @return true if telemetry is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }
}
