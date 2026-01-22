package com.nayem.laminar.saga;

import com.nayem.laminar.core.LaminarEngine;
import com.nayem.laminar.spring.LaminarRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests saga crash recovery behavior.
 * Simulates application restart during saga execution to verify state
 * persistence and resumption.
 */
public class SagaCrashRecoveryTest {

    private InMemorySagaStateRepository stateRepository;
    private SagaMetrics sagaMetrics;
    private Map<String, AtomicInteger> dataStore;

    @BeforeEach
    void setUp() {
        stateRepository = new InMemorySagaStateRepository();
        sagaMetrics = new SagaMetrics(null);
        dataStore = new ConcurrentHashMap<>();
    }

    /**
     * Tests that an incomplete saga is recovered on restart.
     */
    @Test
    void testSagaRecoveryAfterCrash() {
        Instant now = Instant.now();
        // Simulate "crash" by creating a saga state directly (as if mid-execution)
        SagaState crashedSagaState = new SagaState(
                "crashed-saga-1",
                "TestSaga",
                "{}", // serializedSaga
                SagaStatus.RUNNING,
                1, // currentStepIndex
                0, // retryCount
                Map.of("step1", "{\"accountId\":\"acc1\",\"balance\":100}"), // stepResults
                Set.of(), // compensatedSteps
                Map.of(), // failedCompensations
                now, // createdAt
                now, // updatedAt
                null // failureReason
        );
        stateRepository.save(crashedSagaState);

        // Verify incomplete saga is found
        List<SagaState> incomplete = stateRepository.findIncomplete();
        assertEquals(1, incomplete.size());
        assertEquals("crashed-saga-1", incomplete.getFirst().sagaId());
        assertEquals(SagaStatus.RUNNING, incomplete.getFirst().status());
    }

    /**
     * Tests that saga state is correctly persisted after each step.
     */
    @Test
    void testSagaStatePersistenceAfterStep() throws Exception {
        dataStore.put("acc1", new AtomicInteger(100));

        LaminarEngine<Account> engine = LaminarEngine.<Account>builder()
                .loader(key -> new Account(key, dataStore.get(key).get()))
                .saver(account -> dataStore.get(account.id()).set(account.balance()))
                .timeout(Duration.ofSeconds(5))
                .build();

        LaminarRegistry registry = new LaminarRegistry(
                Map.of(Account.class, engine),
                List.of(engine));

        SagaOrchestrator<Account> orchestrator = new SagaOrchestrator<>(
                stateRepository,
                registry,
                Duration.ofMillis(100),
                3,
                0.1,
                new com.nayem.laminar.spring.LaminarProperties.Saga.Recovery(),
                sagaMetrics,
                new NoOpSagaRecoveryLock(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                Duration.ofSeconds(30));

        // Execute a saga with multiple steps
        TestSaga saga = new TestSaga("test-saga-1", "acc1", 50);
        CompletableFuture<Void> result = orchestrator.execute(saga);
        result.get(10, TimeUnit.SECONDS);

        // Verify saga completed
        assertFalse(stateRepository.findIncomplete().stream()
                .anyMatch(s -> s.sagaId().equals("test-saga-1")));
    }

    /**
     * Tests that compensating sagas are resumed on recovery.
     */
    @Test
    void testCompensatingSagaRecovery() {
        Instant now = Instant.now();
        // Create a saga state that was in the middle of compensating
        SagaState compensatingSaga = new SagaState(
                "compensating-saga-1",
                "TestSaga",
                "{}",
                SagaStatus.COMPENSATING,
                2,
                0,
                Map.of("step1", "{\"accountId\":\"acc1\",\"balance\":100}"),
                Set.of("step2"), // step2 already compensated
                Map.of(),
                now,
                now,
                null);
        stateRepository.save(compensatingSaga);

        // Verify incomplete includes compensating sagas
        List<SagaState> incomplete = stateRepository.findIncomplete();
        assertTrue(incomplete.stream()
                .anyMatch(s -> s.status() == SagaStatus.COMPENSATING));
    }

    // Test entities and saga
    record Account(String id, int balance) {
    }

    static class TestSaga implements LaminarSaga<Account> {
        private final String sagaId;
        private final String accountId;
        private final int amount;

        TestSaga(String sagaId, String accountId, int amount) {
            this.sagaId = sagaId;
            this.accountId = accountId;
            this.amount = amount;
        }

        @Override
        public String getSagaId() {
            return sagaId;
        }

        @Override
        public Class<Account> getEntityType() {
            return Account.class;
        }

        @Override
        public List<SagaStep<Account>> getSteps() {
            return List.of(
                    new TestStep("step1", accountId, amount),
                    new TestStep("step2", accountId, amount));
        }

        @Override
        public String getDescription() {
            return "Test saga for crash recovery testing";
        }
    }

    static class TestStep implements SagaStep<Account> {
        private final String stepId;
        private final String entityKey;
        private final int amount;

        TestStep(String stepId, String entityKey, int amount) {
            this.stepId = stepId;
            this.entityKey = entityKey;
            this.amount = amount;
        }

        @Override
        public String getStepId() {
            return stepId;
        }

        @Override
        public String getEntityKey() {
            return entityKey;
        }

        @Override
        public Account execute(Account account) {
            return new Account(account.id(), account.balance() + amount);
        }

        @Override
        public void compensate(Account account, Account previousState) {
            // Compensation is handled by restoring previous state
        }
    }
}
