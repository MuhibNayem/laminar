# Laminar Operations Runbook

## Overview
This document provides operational guidance for running and troubleshooting the Laminar request batching and saga orchestration library in production environments.

## Table of Contents
- [Manual Saga Intervention](#manual-saga-intervention)
- [Recovery Troubleshooting](#recovery-troubleshooting)
- [Metrics Interpretation](#metrics-interpretation)
- [Common Failure Scenarios](#common-failure-scenarios)
- [Redis Key Structure](#redis-key-structure)
- [Monitoring and Alerting](#monitoring-and-alerting)

---

## Manual Saga Intervention

### Viewing Saga State

**In-Memory State Store:**
- State is ephemeral and will be lost on restart
- Use logs and metrics to track saga progress

**Redis State Store:**
```bash
# List all sagas
redis-cli KEYS "laminar:saga:state:*"

# Get saga state
redis-cli GET "laminar:saga:state:<saga-id>"

# View saga lock
redis-cli GET "laminar:saga:lock:<saga-id>"
```

### Manual Saga Completion

If a saga is stuck and you need to manually mark it as completed:

```bash
# For Redis store
redis-cli DEL "laminar:saga:state:<saga-id>"
redis-cli DEL "laminar:saga:lock:<saga-id>"
```

**WARNING:** Only do this after verifying that:
1. All saga steps have been executed successfully
2. The application state is consistent
3. No recovery process is currently handling this saga

### Manual Saga Compensation

If you need to trigger manual compensation:

1. Identify the saga state in Redis
2. Check which steps have completed (`stepResults` field)
3. Manually execute compensation logic for completed steps in reverse order
4. Mark saga as FAILED or delete the state

### Force Recovery

To force immediate recovery check:

```bash
# Restart the application (triggers recovery on startup)
# OR wait for next recovery interval (default: 30s)
```

---

## Recovery Troubleshooting

### Saga Stuck in RUNNING State

**Symptoms:**
- Saga remains in RUNNING status beyond expected duration
- No progress in logs
- Metrics show high `laminar.saga.active{status="RUNNING"}`

**Diagnosis:**
1. Check if saga timeout is configured and if it's been exceeded
2. Check if recovery lock is held by another instance
3. Review logs for exceptions during step execution
4. Verify that the application instance that started the saga is still running

**Resolution:**
```bash
# Check recovery lock
redis-cli GET "laminar:saga:lock:<saga-id>"
redis-cli TTL "laminar:saga:lock:<saga-id>"

# If lock is stale (TTL expired but key exists), delete it
redis-cli DEL "laminar:saga:lock:<saga-id>"

# Monitor recovery logs
tail -f application.log | grep "Recovering saga"
```

### Saga Stuck in COMPENSATING State

**Symptoms:**
- Saga remains in COMPENSATING status
- Compensation logs show repeated failures

**Diagnosis:**
1. Check compensation step failure logs
2. Verify that compensating transactions are idempotent
3. Check if compensation timeout is being hit

**Resolution:**
1. Fix the underlying issue causing compensation to fail
2. Restart the application to trigger recovery
3. If compensation cannot succeed, manually verify and clean up

### Recovery Loop Not Running

**Symptoms:**
- No "Started Saga recovery loop" log message
- Incomplete sagas are not being recovered

**Diagnosis:**
1. Check if recovery is enabled: `laminar.saga.recovery.enabled=true`
2. Verify recovery interval configuration
3. Check for exceptions in recovery thread

**Resolution:**
```yaml
# application.yml
laminar:
  saga:
    recovery:
      enabled: true
      interval: 30s
```

### Duplicate Recovery Attempts

**Symptoms:**
- Multiple instances attempting to recover the same saga
- Logs show lock acquisition failures

**Diagnosis:**
1. Check if distributed locking is enabled
2. Verify Redis connectivity
3. Check lock TTL configuration

**Resolution:**
```yaml
# application.yml
laminar:
  saga:
    lock:
      enabled: true
      provider: redis
      ttl: 30s
```

---

## Metrics Interpretation

### Key Metrics

#### `laminar.saga.active{status="*"}`
**Type:** Gauge  
**Description:** Number of sagas currently in each status  
**Tags:** status (PENDING, RUNNING, COMPENSATING, COMPLETED, FAILED)

**Healthy Values:**
- RUNNING: Low, transient
- COMPENSATING: Very low or zero
- COMPLETED: Constantly increasing
- FAILED: Very low or zero

**Alert If:**
- RUNNING > 100 for > 5 minutes (possible stuck sagas)
- COMPENSATING > 0 for > 5 minutes (compensation issues)
- FAILED rate increasing rapidly

#### `laminar.saga.duration`
**Type:** Timer  
**Description:** Total duration from saga start to completion/failure

**Healthy Values:**
- p50: < 1s
- p95: < 5s
- p99: < 10s

**Alert If:**
- p95 > saga timeout threshold
- p99 consistently near timeout value

#### `laminar.saga.step.execution{step="*"}`
**Type:** Timer  
**Description:** Duration of individual step execution

**Analysis:**
- Identify slow steps
- Detect performance degradation
- Plan capacity

#### `laminar.saga.compensation.count`
**Type:** Counter  
**Description:** Total number of compensations triggered

**Healthy Values:**
- Very low (<1% of total sagas)
- Stable rate

**Alert If:**
- Sudden spike (indicates systemic issue)
- Rate > 5% of total sagas

#### `laminar.saga.recovery.attempts`
**Type:** Counter  
**Description:** Number of saga recovery attempts

**Healthy Values:**
- Low, correlated with pod restarts
- Zero in stable environment

**Alert If:**
- Increasing continuously (sagas not recovering successfully)

#### `laminar.saga.timeout.count`
**Type:** Counter  
**Description:** Number of sagas that timed out

**Healthy Values:**
- Zero

**Alert If:**
- Any timeouts occur (indicates saga timeout too aggressive or performance issue)

---

## Common Failure Scenarios

### Scenario 1: Split-Brain During Recovery

**Symptoms:**
- Multiple instances simultaneously recovering the same saga
- Duplicate step executions visible in logs
- Redis lock contention warnings
- Inconsistent entity state after recovery

**Root Cause:**
- Network partition between application instances and Redis
- Redis failover during recovery
- Clock skew causing lock TTL miscalculations
- Distributed lock not enabled

**Impact:**
- **High Risk:** Duplicate transactions (e.g., double debits, duplicate messages)
- Data inconsistency across entities
- Compensation conflicts

**Detection:**
```bash
# Check for duplicate saga executions
grep "Recovering saga <saga-id>" application.log | sort | uniq -c

# Monitor lock contention
redis-cli MONITOR | grep "laminar:saga:lock"

# Check Redis failover events
redis-cli INFO replication
```

**Resolution:**
1. **Immediate:**
   ```yaml
   # Ensure distributed locking is enabled
   laminar:
     saga:
       recovery:
         distributed-locking: true
         interval: 60s  # Increase interval to reduce contention
   ```

2. **Verify lock acquisition:**
   ```bash
   # Check lock holder
   redis-cli GET "laminar:saga:lock:<saga-id>"
   
   # Verify TTL is adequate
   redis-cli TTL "laminar:saga:lock:<saga-id>"
   # Should be > recovery interval
   ```

3. **Post-incident:**
   - Audit affected sagas for duplicate steps
   - Verify entity state consistency
   - Review compensation logic for idempotency
   - Implement idempotency keys at step level if needed

**Prevention:**
```yaml
laminar:
  saga:
    recovery:
      distributed-locking: true
      lock-ttl: 90s  # 3x recovery interval
      lock-retry-backoff: 5s
      lock-max-retries: 3
```

### Scenario 2: Network Partition Between App and Redis

**Symptoms:**
- All saga dispatches failing with "State repository unavailable"
- Existing sagas frozen mid-execution
- Cluster mutations not being processed
- Recovery loop throwing connection exceptions

**Root Cause:**
- Redis server unreachable (network partition, firewall rules)
- Redis authentication failure
- Redis server overloaded/crashed
- DNS resolution failure

**Impact:**
- **Critical:** All saga orchestration halted
- New sagas cannot start
- In-flight sagas cannot progress or compensate
- Potential data loss if using in-memory fallback

**Detection:**
```bash
# Check Redis connectivity
redis-cli -h <redis-host> -p <redis-port> PING

# Monitor connection errors in logs
grep "RedisConnectionException\|Connection refused" application.log

# Check application health endpoint
curl http://localhost:8080/actuator/health | jq '.components.redis'
```

**Resolution:**
1. **Immediate (Production):**
   ```yaml
   # Enable connection retry with exponential backoff
   spring:
     redis:
       timeout: 5s
       lettuce:
         pool:
           max-active: 20
           max-wait: 10s
         shutdown-timeout: 10s
   ```

2. **Temporary Workaround (Non-Production Only):**
   ```yaml
   laminar:
     saga:
       state-store: memory  # ⚠️ Loses recovery capability
       recovery:
         enabled: false
   ```

3. **Restore Redis:**
   - Verify Redis health: `redis-cli INFO stats`
   - Check network connectivity: `telnet <redis-host> <port>`
   - Review Redis logs for errors
   - Failover to Redis replica if using Sentinel/Cluster

4. **Post-Recovery:**
   - Verify saga state integrity in Redis
   - Trigger manual recovery for stuck sagas
   - Monitor recovery lag time

**Prevention:**
- Use Redis Sentinel or Redis Cluster for HA
- Configure connection pooling adequately
- Set up Redis monitoring and alerts
- Document Redis failover procedures

### Scenario 3: Thundering Herd on Recovery

**Symptoms:**
- All instances attempting to recover all sagas simultaneously after deployment
- Redis lock contention
- High CPU and network usage

**Root Cause:**
- All instances started at same time
- Recovery interval aligned

**Prevention:**
```yaml
laminar:
  saga:
    lock:
      enabled: true  # Enable distributed locking
    recovery:
      interval: 30s  # Stagger by randomizing (built-in jitter)
    retry:
      jitter-percent: 50  # Add jitter to backoff
```

### Scenario 6: Cascading Saga Failures Due to Downstream Service Outage

**Symptoms:**
- Sudden spike in saga failures (>50% failure rate)
- All failures at same step
- Compensation storm overwhelming system
- Recovery loop continuously retrying failed sagas

**Root Cause:**
- Downstream service (database, API, queue) unavailable
- No circuit breaker pattern implemented
- Retry exhaustion triggering mass compensation

**Impact:**
- **Severe:** System-wide saga processing halted
- Backpressure affecting upstream clients
- Resource exhaustion from retry attempts
- Potential inconsistent state across many entities

**Detection:**
```bash
# Check failure rate by step
curl http://localhost:8080/actuator/metrics/laminar.saga.step.execution | \
  jq '.tags[] | select(.tag == "step")'

# Monitor compensation rate
curl http://localhost:8080/actuator/metrics/laminar.saga.compensation.count

# Check error patterns in logs
grep "Saga.*failed" application.log | \
  grep -oP 'step \K\w+' | sort | uniq -c | sort -rn
```

**Resolution:**
1. **Stop the Bleeding:**
   ```yaml
   # Temporarily disable saga processing for affected entity types
   # (Requires custom feature flag implementation)
   laminar:
     saga:
       enabled: false  # Last resort - stops all sagas
   ```

2. **Implement Circuit Breaker (Code Change Required):**
   ```java
   public class ResilientSagaStep implements SagaStep<T> {
       private final CircuitBreaker circuitBreaker;
       
       @Override
       public T execute(T entity) {
           return circuitBreaker.executeSupplier(() -> {
               // Your step logic
           });
       }
   }
   ```

3. **Drain Stuck Sagas:**
   - Wait for downstream service recovery
   - Trigger batch recovery: restart all instances
   - Monitor recovery progress

4. **Post-Incident:**
   - Add circuit breaker to affected steps
   - Implement graceful degradation
   - Add step-level timeouts

**Prevention:**
- Implement circuit breakers for all external dependencies
- Add chaos engineering tests (Toxiproxy, Chaos Monkey)
- Set up step-level SLA monitoring

### Scenario 2: Saga Timeout Too Aggressive

**Symptoms:**
- High `laminar.saga.timeout.count`
- Sagas timing out before completion
- Successful operations being rolled back

**Resolution:**
```yaml
laminar:
  saga:
    timeout: 10m  # Increase timeout (default: 5m)
```

### Scenario 3: Compensation Failures

**Symptoms:**
- Sagas stuck in COMPENSATING
- `failedCompensations` populated in saga state
- Application state inconsistent

**Root Cause:**
- Non-idempotent compensation logic
- Dependent resource unavailable
- Race conditions

**Resolution:**
1. Review compensation implementation for idempotency
2. Add retry logic to compensation steps
3. Implement compensation circuit breaker
4. Manual cleanup if necessary

### Scenario 4: Redis Connection Loss

**Symptoms:**
- Saga state repository errors
- Recovery lock failures
- All sagas failing

**Immediate Actions:**
1. Check Redis connectivity
2. Verify Redis credentials and configuration
3. Check network connectivity
4. Review Redis server health

**Temporary Workaround:**
```yaml
laminar:
  saga:
    state-store: memory  # Fall back to in-memory (loses recovery)
    lock:
      enabled: false  # Disable distributed locking
```

### Scenario 5: Memory Leak from Uncompleted Sagas

**Symptoms:**
- Increasing memory usage
- High `laminar.saga.active{status="RUNNING"}`
- Application eventually OOM

**Root Cause:**
- Sagas not completing
- Recovery not running
- Saga state not being cleaned up

**Resolution:**
1. Enable saga timeout
2. Verify recovery is enabled
3. Add monitoring for stuck sagas
4. Implement saga pruning (custom job to clean old sagas)

---

## Redis Key Structure

### State Keys
```
laminar:saga:state:<saga-id>
```
**Format:** JSON string  
**TTL:** None (manual cleanup required)  
**Fields:**
- sagaId
- sagaType
- serializedSaga
- status
- currentStepIndex
- retryCount
- stepResults (map of stepId -> serialized result)
- compensatedSteps (set)
- failedCompensations (map)
- createdAt
- updatedAt
- failureReason

### Lock Keys
```
laminar:saga:lock:<saga-id>
```
**Format:** String (lock holder ID)  
**TTL:** Configurable (default: 30s)  
**Purpose:** Prevent multiple instances from recovering same saga

### Cluster Keys (if cluster mode enabled)
```
laminar:cluster:task:<entity-type>:<shard>
```
**Format:** JSON  
**TTL:** Short-lived  
**Purpose:** Distribute mutations across cluster

---

## Monitoring and Alerting

### Recommended Alerts

#### Critical Alerts

**Saga Failure Rate > 5%**
```promql
rate(laminar_saga_active{status="FAILED"}[5m]) / 
rate(laminar_saga_active[5m]) > 0.05
```
**Action:** Investigate logs, check dependent services

**Saga Timeout**
```promql
increase(laminar_saga_timeout_count[5m]) > 0
```
**Action:** Check saga duration metrics, increase timeout or optimize steps

**Recovery Failures**
```promql
increase(laminar_saga_recovery_attempts[5m]) > 0 AND
  increase(laminar_saga_active{status="COMPLETED"}[5m]) == 0
```
**Action:** Check recovery logs, verify state store connectivity

#### Warning Alerts

**High Saga Duration**
```promql
histogram_quantile(0.95, laminar_saga_duration_bucket) > 5s
```
**Action:** Profile slow steps, optimize

**Stuck Sagas**
```promql
laminar_saga_active{status="RUNNING"} > 50
```
**Action:** Check for deadlocks, verify recovery is working

**Compensation Rate Increasing**
```promql
rate(laminar_saga_compensation_count[5m]) > 0.01
```
**Action:** Investigate failure patterns, check step reliability

### Dashboards

#### Saga Overview Dashboard
- Total saga executions (rate)
- Success rate (%)
- P50/P95/P99 duration
- Active sagas by status (stacked area chart)
- Compensation rate
- Timeout rate

#### Saga Performance Dashboard
- Step execution times (heatmap by step ID)
- Retry distribution
- Backoff effectiveness
- Recovery lag time

#### Saga Health Dashboard
- Failure reasons (pie chart)
- Failed compensations (table)
- Stuck saga count
- Recovery success rate

---

## Performance Tuning Guide

### JVM Configuration for High-Scale Deployments

#### Recommended JVM Flags (Java 25+)

**For 100K+ Workers / 500K req/sec:**

```bash
# Heap sizing
-Xms8g -Xmx8g          # Match min/max to avoid resizing
-XX:MaxDirectMemorySize=2g  # For NIO (Redis, networking)

# Virtual Thread optimizations (JEP 491)
--enable-preview        # Required for Unpinned Monitors
-Djdk.virtualThreadScheduler.parallelism=16  # CPU cores
-Djdk.virtualThreadScheduler.maxPoolSize=256 # Carrier threads

# GC tuning for low-latency
-XX:+UseZGC            # Or G1GC for Java 21
-XX:ZCollectionInterval=5  # Aggressive GC
-XX:ZAllocationSpikeTolerance=2

# Monitoring & Debugging
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/laminar/heap-dumps/
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps
-Xlog:gc*:file=/var/log/laminar/gc.log:time,uptime,level,tags

# Micrometer/JMX
-Dcom.sun.management.jmxremote=true
-Dcom.sun.management.jmxremote.port=9010
-Dcom.sun.management.jmxremote.authenticate=false
-Dcom.sun.management.jmxremote.ssl=false
```

**For Moderate Load (10K workers / 50K req/sec):**

```bash
-Xms2g -Xmx2g
--enable-preview
-Djdk.virtualThreadScheduler.parallelism=8
-XX:+UseG1GC
```

#### Heap Sizing Recommendations

| Scenario | Workers | Req/sec | Heap | Direct Memory | Reasoning |
|----------|---------|---------|------|---------------|-----------|
| **Small** | <1K | <10K | 512MB | 256MB | Dev/testing |
| **Medium** | 10K | 50K | 2GB | 512MB | Single entity type |
| **Large** | 50K | 250K | 4GB | 1GB | Multiple entity types |
| **Extreme** | 100K+ | 500K+ | 8GB+ | 2GB+ | Hot partitions |

**Formula:**
```
Heap = (Active Workers × Worker Overhead) + (Saga State × Avg Saga Count) + Overhead

Worker Overhead: ~50KB per worker (EntityWorker + CoalescingBatch)
Saga State: ~5KB per saga (SagaState + stepResults)
Overhead: ~1GB (Spring, Redis client, Caffeine cache)
```

**Example Calculation (100K workers, 1K active sagas):**
```
Heap = (100,000 × 50KB) + (1,000 × 5KB) + 1GB
     = 5GB + 5MB + 1GB
     = ~6GB (recommend 8GB for safety)
```

### Application Configuration Tuning

#### High-Throughput Configuration

```yaml
laminar:
  # Worker management
  max-waiters: 50000               # Queue size per entity
  max-cached-workers: 200000       # Total workers in memory
  worker-eviction-time: 5m         # Evict idle workers faster
  
  # Timeouts
  timeout: 10s                     # Batch processing timeout
  shutdown-timeout: 30s            # Graceful shutdown window
  
  # Saga tuning
  saga:
    timeout: 5m                    # Saga max execution time
    retry:
      max-attempts: 3              # Reduce retries for fast-fail
      backoff: 1s                  # Lower backoff for throughput
      jitter-percent: 0.3          # Reduce jitter
    recovery:
      interval: 60s                # Less frequent recovery checks
      distributed-locking: true
  
  # Clustering
  cluster:
    enabled: true
    shards: 64                     # More shards = better distribution

# Redis connection pooling
spring:
  redis:
    lettuce:
      pool:
        max-active: 50             # High concurrency
        max-idle: 20
        min-idle: 10
        max-wait: 5s
      shutdown-timeout: 10s
    timeout: 3s
```

#### Low-Latency Configuration (Priority: Response Time)

```yaml
laminar:
  max-waiters: 1000                # Reject quickly at capacity
  timeout: 2s                      # Fast failure
  worker-eviction-time: 30s        # Keep hot workers cached
  
  saga:
    timeout: 30s                   # Tight timeout
    retry:
      max-attempts: 1              # No retries - fail fast
      backoff: 500ms

spring:
  redis:
    timeout: 500ms                 # Aggressive timeout
    lettuce:
      pool:
        max-active: 20
        max-wait: 1s
```

#### High-Availability Configuration (Priority: Reliability)

```yaml
laminar:
  max-waiters: 100000              # Large queue for resilience
  timeout: 30s                     # Allow slow operations
  
  saga:
    timeout: 15m                   # Generous timeout
    retry:
      max-attempts: 10             # Persistent retries
      backoff: 5s
      jitter-percent: 0.5
    recovery:
      enabled: true
      interval: 30s                # Frequent recovery
      distributed-locking: true

spring:
  redis:
    sentinel:                      # Use Redis Sentinel
      master: mymaster
      nodes: redis-1:26379,redis-2:26379,redis-3:26379
    lettuce:
      pool:
        max-active: 30
      shutdown-timeout: 30s
```

### Redis Configuration

**For High-Scale Laminar:**

```redis
# redis.conf

# Memory
maxmemory 16gb
maxmemory-policy allkeys-lru       # Evict old saga states

# Performance
tcp-backlog 511
timeout 0
tcp-keepalive 300

# Persistence (choose one)
# Option 1: RDB (periodic snapshots)
save 900 1                         # Save after 15 min if 1+ key changed
save 300 10
save 60 10000

# Option 2: AOF (append-only, better durability)
appendonly yes
appendfsync everysec               # Balance durability/performance

# Streams (for cluster mode)
stream-node-max-entries 1000       # Limit stream length
stream-node-max-bytes 4096

# Replication (if using Sentinel/Cluster)
min-replicas-to-write 1
min-replicas-max-lag 10
```

### Monitoring & Profiling

#### Key Metrics to Track

1. **Worker Pool Health:**
   ```promql
   laminar_active_workers           # Should grow/shrink naturally
   laminar_worker_evictions_total   # Should be stable
   ```

2. **Backpressure Indicators:**
   ```promql
   laminar_rejections_total         # Should be near zero
   laminar_global_backpressure_hits # Should be rare
   ```

3. **Coalescing Effectiveness:**
   ```promql
   laminar_requests_total / laminar_batch_process_total
   # Higher ratio = better coalescing
   # Target: >100:1 for hot partitions
   ```

4. **GC Impact:**
   ```promql
   jvm_gc_pause_seconds{quantile="0.99"}  # Should be <100ms
   ```

#### Performance Profiling Commands

```bash
# Monitor Virtual Thread usage
jcmd <pid> Thread.dump_to_file -format=json /tmp/threads.json
jq '.threads[] | select(.virtual == true)' /tmp/threads.json | wc -l

# Track carrier thread pinning (JEP 491)
java -Djdk.tracePinnedThreads=full -jar app.jar

# Heap analysis
jcmd <pid> GC.heap_info
jmap -heap <pid>

# Flight Recorder (low overhead profiling)
jcmd <pid> JFR.start name=laminar-profile duration=60s filename=/tmp/profile.jfr
jcmd <pid> JFR.dump name=laminar-profile
```

### Common Performance Issues

#### Issue 1: High Worker Eviction Rate

**Symptom:** `laminar_worker_evictions_total` increasing rapidly  
**Cause:** Workers evicted before being reused  
**Fix:**
```yaml
laminar:
  worker-eviction-time: 15m  # Increase from default 10m
  max-cached-workers: 0      # Disable size limit (use time-based only)
```

#### Issue 2: Frequent GC Pauses

**Symptom:** `jvm_gc_pause_seconds{quantile="0.99"}` >500ms  
**Cause:** Heap too small or wrong GC algorithm  
**Fix:**
```bash
# Switch to ZGC (ultra-low latency)
-XX:+UseZGC -Xmx8g

# Or tune G1GC
-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -Xmx8g
```

#### Issue 3: Redis Connection Pool Exhaustion

**Symptom:** Timeouts waiting for Redis connections  
**Cause:** Pool too small for throughput  
**Fix:**
```yaml
spring:
  redis:
    lettuce:
      pool:
        max-active: 100  # Increase from default
        max-wait: 10s
```

#### Issue 4: Carrier Thread Starvation

**Symptom:** Virtual Threads blocked, low throughput  
**Cause:** Too few carrier threads for workload  
**Fix:**
```bash
# Increase parallelism (default: # CPU cores)
-Djdk.virtualThreadScheduler.parallelism=32
-Djdk.virtualThreadScheduler.maxPoolSize=512
```

---

## Migration Guide

### Mutation Schema Versioning Strategy

#### The Problem

Changing mutation classes breaks deserialization of in-flight mutations stored in Redis:

```java
// Version 1
public class TransferMutation implements Mutation<Account> {
    private BigDecimal amount;
}

// Version 2 (BREAKING CHANGE)
public class TransferMutation implements Mutation<Account> {
    private BigDecimal amount;
    private String currency;  // NEW FIELD
    private TransferType type; // NEW FIELD
}
```

**Impact:**
- Redis streams contain V1 serialized mutations
- Application expects V2 format
- Jackson deserialization fails → requests lost

#### Solution 1: Schema Versioning with Adapters (Recommended)

**Step 1: Add version field to all mutations**

```java
public abstract class VersionedMutation<T> implements Mutation<T> {
    @JsonProperty("_version")
    private int version = 1;  // Default version
    
    public int getVersion() { return version; }
}

public class TransferMutation extends VersionedMutation<Account> {
    private BigDecimal amount;
    private String currency = "USD";  // Default for V1 compatibility
    private TransferType type = TransferType.STANDARD;
    
    // Constructors, methods...
}
```

**Step 2: Create version-specific adapters**

```java
@Component
public class TransferMutationAdapter {
    
    public TransferMutation deserialize(String json) {
        JsonNode node = objectMapper.readTree(json);
        int version = node.has("_version") ? node.get("_version").asInt() : 1;
        
        return switch (version) {
            case 1 -> deserializeV1(node);
            case 2 -> objectMapper.treeToValue(node, TransferMutation.class);
            default -> throw new UnsupportedOperationException("Unknown version: " + version);
        };
    }
    
    private TransferMutation deserializeV1(JsonNode node) {
        // Migrate V1 to V2 format
        BigDecimal amount = node.get("amount").decimalValue();
        return new TransferMutation(
            amount,
            "USD",                    // Default currency for V1
            TransferType.STANDARD     // Default type for V1
        );
    }
}
```

**Step 3: Update ClusterDispatcher/consumers to use adapter**

```java
// In your Redis consumer
MutationEnvelope envelope = objectMapper.readValue(payload, MutationEnvelope.class);
String mutationJson = envelope.getPayload();

// Use adapter instead of direct deserialization
TransferMutation mutation = transferMutationAdapter.deserialize(mutationJson);
```

#### Solution 2: Parallel Deployment (Zero-Downtime Migration)

**Phase 1: Deploy backward-compatible version**

```java
// Version 1.5 (reads both V1 and V2)
public class TransferMutation implements Mutation<Account> {
    private BigDecimal amount;
    
    @JsonProperty(value = "currency", defaultValue = "USD")
    private String currency = "USD";
    
    @JsonProperty(value = "type", defaultValue = "STANDARD")
    private TransferType type = TransferType.STANDARD;
    
    // Jackson will ignore missing fields in V1 payloads
}
```

**Phase 2: Wait for Redis to drain**

```bash
# Monitor Redis stream length
redis-cli XLEN "laminar:stream:Account:0"

# Wait until all old mutations processed (typically < 5 minutes)
```

**Phase 3: Deploy V2 (writes new format)**

```java
// Version 2.0 (writes V2, still reads V1)
public class TransferMutation implements Mutation<Account> {
    @JsonProperty("_version")
    private int version = 2;
    
    private BigDecimal amount;
    private String currency;
    private TransferType type;
    
    // Validation now required
    @PostConstruct
    void validate() {
        if (currency == null) throw new IllegalStateException("Currency required");
    }
}
```

#### Solution 3: Feature Flags for Gradual Rollout

```yaml
# application.yml
laminar:
  mutations:
    transfer:
      version: 2
      enable-v2-fields: true
```

```java
@Component
public class TransferMutation implements Mutation<Account> {
    
    @Value("${laminar.mutations.transfer.enable-v2-fields:false}")
    private boolean enableV2Fields;
    
    @Override
    public void apply(Account account) {
        account.debit(amount);
        
        if (enableV2Fields) {
            account.setCurrency(currency);  // New behavior
            account.recordTransferType(type);
        }
    }
}
```

### Migration Checklist

**Pre-Migration:**
- [ ] Document all mutation classes and their versions
- [ ] Audit Redis for in-flight mutations (`XLEN` all streams)
- [ ] Set up version field in base `Mutation` interface
- [ ] Create adapter classes for each mutation type
- [ ] Add integration tests for version migration
- [ ] Plan rollback strategy

**During Migration:**
- [ ] Deploy backward-compatible version first
- [ ] Monitor error rates and deserialization failures
- [ ] Verify old mutations still process correctly
- [ ] Wait for Redis streams to drain (check `XLEN`)
- [ ] Deploy new version with schema changes
- [ ] Monitor for Jackson deserialization errors

**Post-Migration:**
- [ ] Remove old version adapters after grace period (e.g., 30 days)
- [ ] Update documentation with new schema
- [ ] Archive old mutation versions in Git with tags

### Redis State Cleanup Script

```bash
#!/bin/bash
# cleanup-old-mutations.sh

REDIS_HOST="localhost"
REDIS_PORT="6379"
STREAM_PATTERN="laminar:stream:*"

echo "Finding all Laminar streams..."
STREAMS=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT --scan --pattern "$STREAM_PATTERN")

for STREAM in $STREAMS; do
    LENGTH=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT XLEN "$STREAM")
    echo "Stream: $STREAM, Length: $LENGTH"
    
    if [ "$LENGTH" -eq 0 ]; then
        echo "  -> Empty, skipping"
        continue
    fi
    
    # Read oldest message
    OLDEST=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT XRANGE "$STREAM" - + COUNT 1)
    echo "  -> Oldest message: $OLDEST"
    
    # Trim old messages (keep last 1000)
    redis-cli -h $REDIS_HOST -p $REDIS_PORT XTRIM "$STREAM" MAXLEN ~ 1000
    echo "  -> Trimmed to 1000 messages"
done
```

### Breaking Change Communication Template

```markdown
## BREAKING CHANGE: TransferMutation Schema Update

**Version:** 2.0.0  
**Release Date:** 2026-02-01  
**Migration Deadline:** 2026-03-01

### What's Changing

The `TransferMutation` class now requires two additional fields:
- `currency` (String): ISO 4217 currency code
- `type` (TransferType): STANDARD, EXPRESS, or INTERNATIONAL

### Migration Path

**Option 1: Automatic (Recommended)**
Deploy version 1.5.0 which auto-migrates V1 mutations:
- V1 mutations default to `currency="USD"` and `type=STANDARD`
- No code changes required in your services

**Option 2: Manual**
Update your code to provide new fields:
```java
// Before
new TransferMutation(accountId, amount)

// After
new TransferMutation(accountId, amount, "USD", TransferType.STANDARD)
```

### Timeline

- **2026-01-15:** V1.5.0 released (backward compatible)
- **2026-02-01:** V2.0.0 released (requires new fields)
- **2026-03-01:** V1.x support removed

### Support

For questions: #laminar-support
```

---

## Best Practices

### Development
1. **Make steps idempotent** - All saga steps should be safe to retry
2. **Keep steps fast** - Aim for < 100ms per step
3. **Test compensation** - Verify rollback logic works correctly
4. **Handle partial failures** - Don't assume all-or-nothing
5. **Use typed saga IDs** - Include prefix/version for debugging

### Operations
1. **Enable all monitoring** - Metrics, logs, traces
2. **Set up alerts** - Don't wait for user reports
3. **Regular saga audits** - Review stuck/failed sagas weekly
4. **Capacity planning** - Monitor saga rate trends
5. **Document custom steps** - Maintain runbook for domain-specific logic

### Configuration
1. **Use Redis for state** - In production, always use persistent store
2. **Enable distributed locking** - In multi-instance deployments
3. **Set appropriate timeouts** - Balance responsiveness with completion
4. **Configure jitter** - Prevent thundering herd
5. **Tune recovery interval** - Balance freshness with overhead

---

## Debugging Tools

### Log Correlation
```bash
# Find all logs for a specific saga
grep "saga-id-123" application.log

# Track saga lifecycle
grep "saga-id-123" application.log | grep -E "started|completed|failed|compensat"
```

### Redis Inspection
```bash
# Count sagas by status
redis-cli --scan --pattern "laminar:saga:state:*" | \
  xargs -I {} redis-cli GET {} | \
  jq -r '.status' | \
  sort | uniq -c

# Find old sagas (> 1 day)
redis-cli --scan --pattern "laminar:saga:state:*" | \
  xargs -I {} redis-cli GET {} | \
  jq 'select(.updatedAt < (now - 86400))'
```

### Performance Profiling
```bash
# Identify slowest steps
curl -s http://localhost:8080/actuator/metrics/laminar.saga.step.execution | \
  jq '.measurements[] | select(.statistic == "MAX")'
```

---

## Emergency Procedures

### Disable Saga Processing
```yaml
laminar:
  saga:
    enabled: false
```

### Clear All Saga State
```bash
# ⚠️ DESTRUCTIVE - Only for emergencies
redis-cli --scan --pattern "laminar:saga:*" | xargs redis-cli DEL
```

### Graceful Shutdown
```bash
# Send SIGTERM to allow in-flight sagas to complete
kill -TERM <pid>

# Monitor shutdown
tail -f application.log | grep "Shutting down"
```

---

## Support Contacts

- **On-Call Engineer:** [contact info]
- **Laminar Team:** [contact info]
- **Redis Support:** [contact info]

## Additional Resources

- [Laminar GitHub Repository](https://github.com/your-org/laminar)
- [Saga Pattern Documentation](https://microservices.io/patterns/data/saga.html)
- [Production Incident Postmortems](link-to-incidents)
