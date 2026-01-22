# Laminar: Production-Grade Request Coalescing Engine

**Laminar** is a **production-ready Request Coalescing & Linearization Engine** for Java 25. It solves the "Hot Partition" problem in high-scale systems (Discord, Twitch, Trading platforms) by treating entities as single-threaded Actors and merging operations *before* they reach the database.

---

## 🚀 The Problem: Hot Partitions

Imagine a Chat Channel or Live Stream with 100,000 concurrent users:

1. **The Event**: Someone types "PogChamp" in chat
2. **The Result**: 10,000 users submit "+1 XP" at the exact same millisecond
3. **The Crash**: 10,000 individual `UPDATE user SET xp = xp + 1` queries → row locks → CPU meltdown → cascading failures

## 🛡️ The Solution: Laminar

Laminar intercepts those 10,000 requests, **merges them into ONE** operation (+10,000 XP), and writes to the DB **once**.

```text
[Request A: +1 XP] ────┐
[Request B: +5 XP] ────┤
[Request C: +1 XP] ────┼──▶ [ Laminar ] ──▶ [ Database ]
       ...             │    (Merges to       (1 Write)
[Request N: +1 XP] ────┘     +10,000 XP)
```

**Powered by**: Virtual Threads (Project Loom) + Keyed-Actor Model + Unpinned Monitors (JEP 491)

---

## 🏗️ Production Features

### ✅ Core Capabilities
- **Hot Partition Protection**: Coalesce 100K requests into <10 DB writes
- **Read Coalescing**: `SingleFlightGroup` prevents thundering herd
- **Linearization**: Strict ordering per entity key
- **Fire-and-Forget**: `@Dispatch` annotation for seamless integration

### ✅ Resilience
- **Configurable Timeouts**: Default 30s, prevents DB hangs
- **Backpressure**: Reject excess requests when capacity exceeded
- **Graceful Shutdown**: Drain all pending mutations before exit
- **Error Handling**: Configurable error callbacks

### ✅ Observability
- **Micrometer Metrics**: Auto-wired to Spring Boot Actuator
  - `laminar.requests` - Total mutations dispatched
  - `laminar.batch.process` - Batch processing (tagged by entity)
  - `laminar.shutdown.forced` - Failed graceful shutdowns
- **SLF4J Logging**: Production-grade logging throughout
- **Thread Naming**: Configurable prefixes for monitoring

### ✅ Configuration
- **Spring Boot Properties**: All settings via `application.yml`
- **Horizontal Scaling**: Native support for sharded clustering via Redis
- **Builder API**: Fluent configuration for non-Spring usage
- **Reasonable Defaults**: Works out-of-the-box

---

## ⚠️ What Laminar Is NOT Designed For

Laminar is **not** a replacement for traditional database transactions when you need atomic operations across **multiple different entities**.

### Example: Bank Transfer

```java
// User wants to transfer $100 from Account A to Account B
accountA.withdraw(100);  // Entity: Account A
accountB.deposit(100);   // Entity: Account B
```

This requires both operations to succeed or fail together (atomicity). If `withdraw` succeeds but `deposit` fails, you lose money.

### Why Laminar Doesn't Fit Here

- `getEntityKey()` in a `Mutation` returns a **single key** (e.g., `accountId`).
- Laminar batches mutations **per entity key**, so Account A and Account B would be processed in separate, independent batches.
- There's no built-in mechanism to coordinate a transaction spanning multiple entity keys.

### For Multi-Entity Atomicity, Use:

- **Traditional `@Transactional`** database transactions
- **Saga patterns** (for distributed systems)
- **Two-Phase Commit (2PC)**

---

## 📦 Installation

**Requirements**: Java 25 + Spring Boot 4.0.1 + Redis (optional, for clustering)

```xml
<dependency>
    <groupId>com.nayem</groupId>
    <artifactId>Laminar</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### Gradle

Add the GitLab repository and dependency to your `build.gradle`:

```groovy
repositories {
    mavenCentral()
    maven {
        url "https://gitlab.bracits.com/api/v4/projects/1494/packages/maven"
        credentials(HttpHeaderCredentials) {
            name = 'Private-Token'
            value = "YOUR_PERSONAL_ACCESS_TOKEN" // Or System.getenv("GITLAB_TOKEN")
        }
        authentication {
            header(HttpHeaderAuthentication)
        }
    }
}

dependencies {
    implementation 'com.nayem:Laminar:0.0.1-SNAPSHOT'
}
```

---

## 🌐 Horizontal Scaling (Clustering)

Laminar supports **Distributed Linearization** through a sharded worker model using Redis Streams. This allows you to scale your application across multiple nodes while ensuring that all mutations for a specific entity key are still processed by exactly one worker in the cluster.

### How it Works
1. **Hashing**: Mutations are hashed by their `entityKey`.
2. **Sharding**: The cluster is divided into N shards (default: 16).
3. **Leasing**: Nodes in the cluster compete for "leases" on these shards using distributed locks.
4. **Ordering**: Only the node that owns a shard's lease will consume and process its mutations, preserving strict linearization across the cluster.

### Setup
Add the Redis starter to your project:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

And configure:

```yaml
laminar:
  cluster:
    enabled: true
    shards: 32 # Higher concurrency, more Redis connection usage
```

---

## 🔄 Multi-Entity Transactions (Saga Pattern)

For operations spanning multiple entities (e.g., money transfer between accounts), Laminar provides a robust **Saga** implementation. This ensures reliability even in distributed systems by using a "Execute -> Compensate" workflow.

### Features
- **Request Coalescing**: Saga steps are dispatched to the Laminar Engine and **batched atomically** with other mutations.
- **Guaranteed Atomicity**: Eventually consistent via compensation.
- **Crash Recovery**: Automatically resumes interrupted sagas on startup.
- **Deadlock Prevention**: Automatically orders locks to prevent deadlocks.
- **Idempotency**: Built-in protection against duplicate execution.

### Usage

1. **Define a Saga**:

```java
@Service
public class TransferService {

    @DispatchSaga
    public LaminarSaga<Account> transfer(String fromId, String toId, BigDecimal amount) {
        return new TransferSaga(fromId, toId, amount);
    }
}
```

2. **Implement the Saga & Steps**:

```java
public class TransferSaga implements LaminarSaga<Account> {
    // ... constructor ...

    @Override
    public List<SagaStep<Account>> getSteps() {
        return List.of(
            new DebitStep(fromId, amount),
            new CreditStep(toId, amount)
        );
    }
}

public class DebitStep implements SagaStep<Account> {
    @Override
    public Account execute(Account account) {
        account.withdraw(amount);
        return account; // Return state for compensation logic
    }

    @Override
    public void compensate(Account account, Account previousState) {
        account.deposit(amount); // Reverse the operation
    }
}
```

### Configuration

```yaml
laminar:
  saga:
    enabled: true
    state-store: memory # 'memory' (default) or 'redis'
    retry:
      max-attempts: 5
      backoff: 2s
    recovery:
      enabled: true
      interval: 1m
```

### Reducing Boilerplate with Functional APIs

For simple sagas, use `SagaBuilder` and `FunctionalSagaStep` to avoid creating separate classes:

```java
@Service
public class TransferService {

    @DispatchSaga
    public LaminarSaga<Account> transfer(String fromId, String toId, BigDecimal amount) {
        return SagaBuilder.<Account>newSaga("transfer-" + UUID.randomUUID(), Account.class)
            .description("Transfer " + amount + " from " + fromId + " to " + toId)
            .step(FunctionalSagaStep.<Account>builder()
                .stepId("debit-" + fromId)
                .entityKey(fromId)
                .execute(account -> {
                    account.withdraw(amount);
                    return account;
                })
                .compensate((account, prev) -> account.deposit(amount))
                .build())
            .step(FunctionalSagaStep.<Account>builder()
                .stepId("credit-" + toId)
                .entityKey(toId)
                .execute(account -> {
                    account.deposit(amount);
                    return account;
                })
                .compensate((account, prev) -> account.withdraw(amount))
                .build())
            .onSuccess(() -> log.info("Transfer completed"))
            .onFailure(e -> log.error("Transfer failed", e))
            .build();
    }
}
```

Similarly, use `FunctionalMutation` for simple mutations:

```java
// Instead of creating a separate XpMutation class:
FunctionalMutation<User> mutation = FunctionalMutation.of(
    userId,
    user -> user.addXp(50)
);
```

For Redis-backed saga state (required for production):
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

Enable cluster mode in `application.yml`:
```yaml
laminar:
  cluster:
    enabled: true
    shards: 32 # Higher shards = better distribution across many nodes
```

---

## 🗑️ Dead Letter Queue (DLQ)

Laminar provides a **Dead Letter Queue** to safely capture mutations that fail repeatedly, ensuring data integrity even in the face of unrecoverable errors (e.g., bad data, schema violations).

### Features
- **Automatic Capture**: Mutations are moved to DLQ after exhausting retries.
- **Context Preservation**: Stores exception message, stack trace type, and original payload.
- **Pluggable Storage**:
  - `memory`: Lightweight, development-only (cleared on restart).
  - `redis`: Durable storage using `StringRedisTemplate`.

### Enable DLQ
```yaml
laminar:
  dlq:
    enabled: true
    store: redis    # 'memory' or 'redis'
    ttl: 7d         # Retention period
```

---

## 🛠️ Quick Start

### 1. Define a Mutation
A Mutation is an atomic unit of change logic that knows how to merge with others.

```java
public class XpMutation implements Mutation<User> {
    private final String userId;
    private final long amount;

    @Override
    public String getEntityKey() {
        return userId; // Routing key
    }

    @Override
    public Mutation<User> coalesce(Mutation<User> other) {
        if (other instanceof XpMutation next) {
            return new XpMutation(userId, this.amount + next.amount);
        }
        return other;
    }

    @Override
    public void apply(User user) {
        user.addXp(amount);
    }
}
```

**Optional: Add Versioning for Safe Schema Evolution**

For mutations used in cluster mode, implement `VersionedMutation` to enable zero-downtime schema changes:

```java
public class XpMutation implements Mutation<User>, VersionedMutation {
    private final String userId;
    private final long amount;
    private final String reason; // Added in v2

    @Override
    public int getVersion() {
        return 2; // Increment when schema changes
    }

    @Override
    public String getEntityKey() {
        return userId;
    }

    @Override
    public Mutation<User> coalesce(Mutation<User> other) {
        if (other instanceof XpMutation next) {
            return new XpMutation(userId, this.amount + next.amount, reason);
        }
        return other;
    }

    @Override
    public void apply(User user) {
        user.addXp(amount, reason);
    }
}
```

**⚠️ Coalescing Best Practices:**
- **Never return 'this' repeatedly**: Causes cyclic coalescing detection
- **Keep coalesce() fast**: Avoid DB calls or expensive operations
- **Test merge logic**: Ensure correctness under high concurrency

See [Migration Guide](OPERATIONS.md#migration-guide) for schema versioning strategies.

### 2. Spring Boot Integration (Step-by-Step)

Laminar auto-configures itself with **zero XML or manual wiring**. Just follow these 3 steps:

---

#### **Step 1: Add the Dependency**

In your `pom.xml`:

```xml
<dependency>
    <groupId>com.nayem</groupId>
    <artifactId>Laminar</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

---

#### **Step 2: Create Your Entity and Mutation**

Let's say you have a `User` entity in your database:

```java
@Entity
public class User {
    @Id
    private String id;
    private long xp;
    
    public void addXp(long amount) {
        this.xp += amount;
    }
    
    // Getters, setters, constructors...
}
```

Create a `Mutation` that describes **how to change** a User:

```java
package com.yourapp.mutations;

import com.nayem.laminar.core.Mutation;

public class AddXpMutation implements Mutation<User> {
    private final String userId;
    private final long xpToAdd;

    public AddXpMutation(String userId, long xpToAdd) {
        this.userId = userId;
        this.xpToAdd = xpToAdd;
    }

    @Override
    public String getEntityKey() {
        return userId; // This routes all mutations for the same user to one worker
    }

    @Override
    public Mutation<User> coalesce(Mutation<User> other) {
        // If another AddXpMutation comes in while we're processing, MERGE them!
        if (other instanceof AddXpMutation next) {
            return new AddXpMutation(userId, this.xpToAdd + next.xpToAdd);
        }
        return other; // Can't merge, process separately
    }

    @Override
    public void apply(User user) {
        user.addXp(xpToAdd); // Actually modify the user
    }
}
```

---

#### **Step 3: Create a Handler (Tell Laminar how to Load/Save)**

Create a Spring `@Component` that implements `LaminarHandler`:

```java
package com.yourapp.laminar;

import com.nayem.laminar.spring.LaminarHandler;
import com.yourapp.entities.User;
import com.yourapp.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class UserLaminarHandler implements LaminarHandler<User> {
    
    @Autowired
    private UserRepository userRepository;
    
    @Override
    public User load(String userId) {
        // Laminar will call this to fetch the entity from the database
        return userRepository.findById(userId)
                .orElse(new User(userId)); // Or throw exception if you prefer
    }

    @Override
    public void save(User user) {
        // Laminar will call this ONCE after applying all merged mutations
        userRepository.save(user);
    }

    @Override
    public Class<User> getEntityType() {
        return User.class; // Tells Laminar this handler manages "User" entities
    }
}
```

**That's it for setup!** Laminar will auto-detect this `@Component` and configure itself.

---

#### **Step 4: Use It in Your Service**

Now in any `@Service` or `@RestController`, annotate methods with `@Dispatch`:

```java
package com.yourapp.services;

import com.nayem.laminar.spring.Dispatch;
import com.nayem.laminar.core.Mutation;
import com.yourapp.mutations.AddXpMutation;
import com.yourapp.entities.User;
import org.springframework.stereotype.Service;

@Service
public class GameService {

    @Dispatch
    public Mutation<User> rewardPlayer(String userId, long points) {
        // Just return the mutation - Laminar handles the rest!
        return new AddXpMutation(userId, points);
    }
}
```

**What happens when you call this?**

1. `@Dispatch` intercepts the method call
2. Extracts the `Mutation` from the return value
3. Routes it to the correct `EntityWorker` (by `userId`)
4. Coalesces with any other concurrent mutations for the same user
5. Calls `load()` → `apply()` → `save()` atomically
6. Returns `null` to your caller (fire-and-forget)

---

#### **Step 5: Call It!**

```java
@RestController
public class GameController {

    @Autowired
    private GameService gameService;

    @PostMapping("/api/reward/{userId}")
    public ResponseEntity<String> rewardUser(@PathVariable String userId) {
        // This might be called 10,000 times in one second!
        gameService.rewardPlayer(userId, 100);
        
        // Laminar will coalesce them into ~1-10 DB writes automatically
        return ResponseEntity.ok("Reward queued!");
    }
}
```

**Result**: 10,000 API calls → ~5 database writes (with perfect data integrity!)

---

## ⚙️ Configuration

### Spring Boot Properties (`application.yml`)

```yaml
laminar:
  # Backpressure: Max pending requests per entity worker
  max-waiters: 10000                  # Default: 1000
  
  # Safety: Batch size and coalescing limits
  max-batch-size: 50000               # Default: 10000 (prevents memory exhaustion)
  max-coalesce-iterations: 1000       # Default: 1000 (detects cyclic coalescing)
  
  # Resilience: Timeout for load/save operations
  timeout: 5s                         # Default: 30s
  
  # Performance: Worker cache eviction time
  worker-eviction-time: 5m            # Default: 10m
  
  # Limits: Max concurrent entity workers (0 = unbounded)
  max-cached-workers: 100000          # Default: 0
  
  # Lifecycle: Graceful shutdown timeout
  shutdown-timeout: 30s               # Default: 10s
  shutdown-polling-interval: 50ms     # Default: 100ms
  
  # Observability: Thread naming for monitoring
  thread-name-prefix: "game-worker-"  # Default: "laminar-worker-"

  # Clustering (Optional)
  cluster:
    enabled: true
    shards: 16                        # Concurrent Redis Stream consumers

  # Saga (Multi-Entity Transactions)
  saga:
    enabled: true
    state-store: redis                # Persistence for recovery
    retry:
      max-attempts: 3
      backoff: 1s
    recovery:
      enabled: true
      interval: 30s                   # frequency of crash recovery scans

  # Dead Letter Queue
  dlq:
    enabled: true
    store: redis
    ttl: 7d                           # Automatic cleanup after 7 days
```

**New Safety Features:**
- **`max-batch-size`**: Prevents memory bombs from unbounded coalescing
- **`max-coalesce-iterations`**: Detects if `mutation.coalesce(other)` returns 'this' indefinitely

### Programmatic Configuration (Non-Spring)

```java
LaminarEngine<User> engine = LaminarEngine.<User>builder()
    .loader(id -> userRepo.findById(id))
    .saver(user -> userRepo.save(user))
    .timeout(Duration.ofSeconds(5))
    .maxWaiters(50_000)
    .maxBatchSize(100_000)              // NEW: Batch size limit
    .maxCoalesceIterations(1000)        // NEW: Cyclic coalescing detection
    .maxCachedWorkers(100_000)
    .threadNamePrefix("user-worker-")
    .metrics(meterRegistry)
    .onError(err -> log.error("Mutation failed", err))
    .build();

engine.dispatch(new XpMutation("user_123", 10));
```

---

## 📊 Performance Benchmark

From `LaminarLoadTest` (100K concurrent requests):

| Metric | Value |
|--------|-------|
| **Incoming Requests** | 100,000 |
| **Actual DB Writes** | 9-15 (varies) |
| **Coalescing Factor** | ~10,000x |
| **Execution Time** | ~200ms |
| **Throughput** | 500K req/sec |

**Real-world impact**: Reduced database load from 10,000 TPS to <50 TPS while maintaining linearizability.

---

## 🔬 How It Works

### Architecture

### Architecture

Laminar is designed as a layered system composed of three main subsystems: the **Core Coalescing Engine**, the **Saga Orchestrator**, and the **Cluster Manager**.

```mermaid
graph TD
    %% Clients
    Client([Client Request])

    %% Spring Integration Layer
    subgraph "Spring Integration Layer"
        AOP_Core["LaminarAspect<br/>(@Dispatch)"]
        AOP_Saga["SagaAspect<br/>(@DispatchSaga)"]
    end

    Client --> AOP_Core
    Client --> AOP_Saga

    %% Saga Subsystem
    subgraph "Saga Subsystem"
        Orchestrator[SagaOrchestrator]
        SagaStore[(Saga State Store<br/>Redis/Memory)]
        Recovery[Crash Recovery]
    end

    AOP_Saga --> Orchestrator
    Orchestrator <--> SagaStore
    Orchestrator -.->|Resume| Recovery

    %% Core Engine
    subgraph "Laminar Core Engine"
        Router["LaminarEngine<br/>(Router)"]
        WorkerPool["Worker Pool<br/>(Caffeine Cache)"]
        
        subgraph "Virtual Thread Actor"
            Worker[EntityWorker]
            Batch[CoalescingBatch]
        end
    end

    AOP_Core --> Router
    Orchestrator -->|Execute Step| Router
    Orchestrator -->|Compensate| Router

    Router -->|Get/Create| WorkerPool
    WorkerPool --> Worker
    Worker -->|Accumulate| Batch

    %% Cluster Layer
    subgraph "Cluster Layer (Optional)"
        RedisStream(Redis Streams<br/>Sharded Work Queue)
        ClusterMgr[ClusterWorkerManager]
    end

    Router -.->|If Clustered| RedisStream
    RedisStream -.->|Consume| ClusterMgr
    ClusterMgr --> Router

    %% Persistence
    subgraph "Persistence"
        DB[(Primary Database)]
    end

    Batch -->|Load-Apply-Save| DB
```

#### Detailed View (ASCII)

```
                                 ┌──────────────┐
                                 │ Client Req   │
                                 └──────┬───────┘
                                        │
                 ┌──────────────────────┴──────────────────────┐
                 ▼                                             ▼
       ┌─────────────────────┐                       ┌────────────────────┐
       │    LaminarAspect    │                       │     SagaAspect     │
       │     (@Dispatch)     │                       │   (@DispatchSaga)  │
       └─────────┬───────────┘                       └─────────┬──────────┘
                 │                                             │
                 │                                   ┌─────────▼──────────┐
                 │                                   │  SagaOrchestrator  │◄──┐
                 │                                   └─────────┬──────────┘   │
                 │                                             │           ┌──┴──────┐
                 │                                             │           │SagaState│
                 ▼                                             ▼           └─────────┘
       ┌──────────────────────────────────────────────────────────────────┐
       │                       Laminar Core Engine                        │
       │                                                                  │
       │  ┌──────────────┐       ┌──────────────┐       ┌──────────────┐  │
       │  │ LaminarRouter│──────►│  WorkerPool  │──────►│ EntityWorker │  │
       │  └──────┬───────┘       └──────────────┘       │   (Virtual)  │  │
       │         │                                      └──────┬───────┘  │
       │       (If Clustered)                                  │          │
       │         │                                             ▼          │
       │         ▼                                      ┌──────────────┐  │
       │  ┌─────────────┐                               │  Coalescing  │  │
       │  │Redis Streams│                               │    Batch     │  │
       │  └──────┬──────┘                               └──────┬───────┘  │
       │         │                                             │          │
       │         ▼                                             │          │
       │  ┌─────────────┐                                      │          │
       │  │ClusterMgr   │                                      │          │
       │  └──────┬──────┘                                      │          │
       │         │                                             │          │
       │         ▼                                             │          │
       │  (To Router)                                          │          │
       └───────────────────────────────────────────────────────┼──────────┘
                                                               │
                                                               ▼
                                                      ┌──────────────────┐
                                                      │ Primary Database │
                                                      └──────────────────┘
```

### Component Breakdown

1.  **Core Engine**: Handles strict linearization and coalescing.
    *   **LaminarEngine**: The entry point. Routes mutations to local workers or the cluster layer.
    *   **EntityWorker**: A lightweight "Actor" running on a **Virtual Thread**. It processes one batch at a time for a specific entity key.
    *   **CoalescingBatch**: The "accumulator". It merges incoming mutations using your custom `coalesce()` logic while the worker is busy IO-waiting.

2.  **Saga Orchestrator**: Manages multi-step, multi-entity transactions.
    *   **Orchestrator**: Executes steps sequentially. If a step fails, it executes compensating actions in reverse order.
    *   **Saga State Store**: Persists the progress of every saga (steps completed, results) to Redis to survive application crashes.

3.  **Cluster Layer**: Enables horizontal scaling.
    *   **Redis Streams**: Acts as a partitioned log. Mutations are hashed to shards (e.g., shard 0-31).
    *   **ClusterWorkerManager**: Ensures each shard is consumed by exactly one node in the cluster (via distributed locks), maintaining global linearization.

### Execution Flow

1. **Routing**: `getEntityKey()` determines which `EntityWorker` handles the request
2. **Virtual Actor**: If no worker exists, spawn a new Virtual Thread instantly
3. **Coalescing**: While worker is busy (DB I/O), incoming requests merge in-memory via `coalesce()`
4. **Linearization**: Worker processes the merged batch atomically
5. **Completion**: All waiting `CompletableFuture`s complete simultaneously

### Read Coalescing (Bonus)

```java
SingleFlightGroup<String> reader = new SingleFlightGroup<>();

// 100 concurrent threads → Only ONE DB call
String profile = reader.doCall("user_123_profile", () -> 
    db.fetchProfile("user_123")
).join();
```

---

## 🧪 Testing

### Run All Tests
```bash
./mvnw clean test
```

### Test Coverage
- **`LaminarLoadTest`**: 100K request coalescing stress test
- **`LaminarSpringTest`**: Spring AOP integration test
- **`SingleFlightTest`**: Read coalescing verification

---

## 🚀 Deployment

### GitLab Package Registry

The project includes CI/CD for automatic deployment:

```bash
# Triggered automatically on push to main or tags
git tag v0.0.1
git push origin v0.0.1
```

Pipeline stages:
1. **Build**: Compile with Java 25
2. **Test**: Run all integration tests
3. **Deploy**: Publish to GitLab Package Registry

---

## 🔧 Troubleshooting

### Issue: "Backpressure: Too many pending requests"

**Cause**: More than `maxWaiters` requests queued for a single entity.

**Solution**:
```yaml
laminar:
  max-waiters: 50000  # Increase limit
```

### Issue: "TimeoutException in DB operation"

**Cause**: Database operation exceeded configured timeout.

**Solution**:
```yaml
laminar:
  timeout: 60s  # Increase timeout
```

### Issue: Workers not shutting down gracefully

**Cause**: Shutdown timeout too short for pending batches.

**Solution**:
```yaml
laminar:
  shutdown-timeout: 30s  # Allow longer drain
```

---

## 📚 Advanced Topics

### Custom Error Handling

```java
LaminarEngine.<User>builder()
    .onError(throwable -> {
        log.error("Mutation failed", throwable);
        alertingService.send("Laminar error", throwable);
    })
    .build();
```

### Metrics Integration

```java
@Configuration
public class MetricsConfig {
    @Bean
    public MeterRegistry meterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }
}
```

Automatically exposes:
- `laminar.requests{entity=User}` - Counter
- `laminar.batch.process{entity=User}` - Counter
- `laminar.shutdown.forced{remaining_workers=5}` - Counter

---

## 📚 Operations & Monitoring

See [**OPERATIONS.md**](OPERATIONS.md) for the complete operational runbook.

### 📋 Comprehensive Operational Guides

| Section | What You'll Learn |
|---------|-------------------|
| [**Failure Scenarios**](OPERATIONS.md#common-failure-scenarios) | Split-brain recovery, network partitions, cascading failures |
| [**Performance Tuning**](OPERATIONS.md#performance-tuning-guide) | JVM flags for 100K+ workers, heap sizing, Redis optimization |
| [**Migration Guide**](OPERATIONS.md#migration-guide) | Zero-downtime mutation schema versioning |
| [**Recovery Troubleshooting**](OPERATIONS.md#recovery-troubleshooting) | Diagnose stuck sagas, lock issues, compensation failures |
| [**Monitoring & Alerts**](OPERATIONS.md#monitoring-and-alerting) | Prometheus queries, Grafana dashboards, alert thresholds |

### 🎯 Quick Performance Reference

**For 100K Workers / 500K req/sec:**

```bash
# JVM Configuration (Java 25+)
-Xms8g -Xmx8g 
--enable-preview
-Djdk.virtualThreadScheduler.parallelism=16
-Djdk.virtualThreadScheduler.maxPoolSize=256
-XX:+UseZGC
-XX:MaxDirectMemorySize=2g
```

```yaml
# Application Configuration
laminar:
  max-waiters: 50000
  max-cached-workers: 200000
  worker-eviction-time: 5m
  cluster:
    enabled: true
    shards: 64

spring:
  redis:
    lettuce:
      pool:
        max-active: 50
```

**Heap Sizing Formula:**
```
Heap = (Active Workers × 50KB) + (Sagas × 5KB) + 1GB overhead

Example: 100K workers + 1K sagas = ~6GB (recommend 8GB)
```

See [**Performance Tuning Guide**](OPERATIONS.md#performance-tuning-guide) for comprehensive configuration.

### 📊 Key Metrics

| Metric | Type | Description | Healthy Range |
|--------|------|-------------|---------------|
| `laminar.saga.active{status}` | Gauge | Active sagas by status | RUNNING < 100 |
| `laminar.saga.duration` | Timer | Saga execution time | p95 < 5s |
| `laminar.batch.process` | Counter | Coalesced batches | Increasing |
| `laminar.saga.compensation.count` | Counter | Compensations triggered | <1% of sagas |
| `laminar.saga.timeout.count` | Counter | Saga timeouts | 0 |
| `laminar.recovery.attempts` | Counter | Recovery attempts | Low |
| `laminar.requests` | Counter | Total requests | High throughput |

### 🔧 Health Checks

Laminar integrates with **Spring Boot Actuator**:

```bash
# Overall health
curl http://localhost:8080/actuator/health

# Detailed Redis health
curl http://localhost:8080/actuator/health/redis

# Metrics endpoint
curl http://localhost:8080/actuator/metrics/laminar.saga.active
```

### 🚨 Critical Failure Scenarios

**1. Split-Brain During Recovery**
- **Symptom:** Multiple instances recovering same saga
- **Fix:** Enable `laminar.saga.recovery.distributed-locking: true`
- [**Full Guide →**](OPERATIONS.md#scenario-1-split-brain-during-recovery)

**2. Network Partition with Redis**
- **Symptom:** All sagas frozen, connection errors
- **Fix:** Use Redis Sentinel/Cluster for HA
- [**Full Guide →**](OPERATIONS.md#scenario-2-network-partition-between-app-and-redis)

**3. Cascading Saga Failures**
- **Symptom:** >50% failure rate, compensation storm
- **Fix:** Implement circuit breakers on saga steps
- [**Full Guide →**](OPERATIONS.md#scenario-6-cascading-saga-failures-due-to-downstream-service-outage)

### 🔄 Migration & Versioning

**Zero-Downtime Mutation Schema Changes:**

```java
// Add versioning to mutations
public abstract class VersionedMutation<T> implements Mutation<T> {
    @JsonProperty("_version")
    private int version = 1;
}

// Create adapters for backward compatibility
public class TransferMutationAdapter {
    public TransferMutation deserialize(String json) {
        int version = extractVersion(json);
        return switch (version) {
            case 1 -> migrateV1toV2(json);
            case 2 -> deserializeV2(json);
        };
    }
}
```

See [**Migration Guide**](OPERATIONS.md#migration-guide) for complete versioning strategies.

---

## 🤝 Contributing

This is a production library for mission-critical systems. Contributions must:
- Include comprehensive tests
- Maintain zero breaking changes
- Follow existing code style
- Update documentation

---

## 📄 License

MIT License - See LICENSE file for details

---

## 🙏 Acknowledgments

Built with:
- **Java 25** - Virtual Threads (Project Loom), JEP 491 (Unpinned Monitors)
- **Spring Boot 4.0** - Modern reactive framework
- **Caffeine** - High-performance caching
- **Micrometer** - Metrics abstraction
- **SLF4J** - Logging facade

---

**Production-Ready. Battle-Tested. Coalesce Everything.**
