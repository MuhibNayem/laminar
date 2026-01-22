package com.nayem.laminar.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayem.laminar.core.LaminarDispatcher;
import com.nayem.laminar.core.Mutation;
import com.nayem.laminar.spring.LaminarProperties;
import com.nayem.laminar.spring.LaminarRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SagaStepLogicTest {

    private SagaOrchestrator<MutableAccount> orchestrator;
    private InMemorySagaStateRepository repository;
    private Map<String, MutableAccount> database;

    @BeforeEach
    void setUp() {
        repository = new InMemorySagaStateRepository();
        database = new ConcurrentHashMap<>();

        // Seed database
        database.put("acc1", new MutableAccount("acc1", 100));
        database.put("acc2", new MutableAccount("acc2", 50));

        // Mock Dispatcher
        class MockDispatcher implements LaminarDispatcher<MutableAccount>, AutoCloseable {
            @Override
            public CompletableFuture<Void> dispatch(Mutation<MutableAccount> mutation) {
                return CompletableFuture.runAsync(() -> {
                    String key = mutation.getEntityKey();
                    MutableAccount entity = database.get(key);
                    try {
                        mutation.apply(entity);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            @Override
            public void close() {
            }
        }

        MockDispatcher dispatcher = new MockDispatcher();
        List<AutoCloseable> resources = List.of(dispatcher);
        LaminarRegistry registry = new LaminarRegistry(Map.of(MutableAccount.class, dispatcher), resources);

        LaminarProperties.Saga.Recovery recoveryConfig = new LaminarProperties.Saga.Recovery();
        recoveryConfig.setEnabled(false);

        orchestrator = new SagaOrchestrator<>(
                repository,
                registry,
                Duration.ofMillis(10), // Backoff
                3, // Max retries
                0.0, // Jitter percent
                recoveryConfig,
                SagaMetrics.noOp(),
                new NoOpSagaRecoveryLock(),
                new ObjectMapper(),
                Duration.ofSeconds(10) // Saga timeout
        );
    }

    @Test
    void testSuccessfulSaga() throws Exception {
        MutableTransferSaga saga = new MutableTransferSaga("saga1", "acc1", "acc2", 20);

        orchestrator.execute(saga).get(5, TimeUnit.SECONDS);

        // Allow slight delay for async updates to settle if any
        Thread.sleep(100);

        assertEquals(80, database.get("acc1").balance);
        assertEquals(70, database.get("acc2").balance);

        SagaState state = repository.findById("saga1").orElse(null);
        // State should be deleted on success
        assertNull(state, "State should be cleaned up after success");
    }

    @Test
    void testFailureState() {
        // Transfer 20 from acc1 to acc2, but acc2 will fail
        MutableTransferSaga saga = new MutableTransferSaga("saga2", "acc1", "fail_acc2", 20);

        // Pre-create the fail account
        database.put("fail_acc2", new MutableAccount("fail_acc2", 0));

        try {
            orchestrator.execute(saga).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Expected
        }

        // Allow time for async compensation callbacks
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
        }

        SagaState state = repository.findById("saga2").orElseThrow();
        // Checked against FAILED status
        assertEquals(SagaStatus.FAILED, state.status());
    }

    // --- Helpers ---

    static class MutableAccount {
        String id;
        int balance;

        public MutableAccount() {} // Jackson needs default constructor
        public MutableAccount(String id, int balance) {
            this.id = id;
            this.balance = balance;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public int getBalance() { return balance; }
        public void setBalance(int balance) { this.balance = balance; }
    }

    static class MutableTransferSaga implements LaminarSaga<MutableAccount> {
        private String sagaId;
        private String fromId;
        private String toId;
        private int amount;

        public MutableTransferSaga() {} // Jackson needs default constructor

        public MutableTransferSaga(String sagaId, String fromId, String toId, int amount) {
            this.sagaId = sagaId;
            this.fromId = fromId;
            this.toId = toId;
            this.amount = amount;
        }

        @Override
        public String getSagaId() {
            return sagaId;
        }

        @Override
        public Class<MutableAccount> getEntityType() {
            return MutableAccount.class;
        }

        @Override
        public List<SagaStep<MutableAccount>> getSteps() {
            return List.of(
                    new MutableDebitStep(fromId, amount),
                    new MutableCreditStep(toId, amount));
        }

        // Getters for Jackson
        public String getFromId() { return fromId; }
        public String getToId() { return toId; }
        public int getAmount() { return amount; }
    }

    static class MutableDebitStep implements SagaStep<MutableAccount> {
        private String accountId;
        private int amount;

        public MutableDebitStep() {} // Jackson
        public MutableDebitStep(String accountId, int amount) {
            this.accountId = accountId;
            this.amount = amount;
        }

        public String getStepId() {
            return "debit-" + accountId;
        }

        public String getEntityKey() {
            return accountId;
        }

        public MutableAccount execute(MutableAccount entity) {
            if (entity.balance < amount)
                throw new RuntimeException("Insufficient funds");
            entity.balance -= amount;
            return entity;
        }

        public void compensate(MutableAccount entity, MutableAccount previousState) {
            entity.balance += amount;
        }

        public String getAccountId() { return accountId; }
        public int getAmount() { return amount; }
    }

    static class MutableCreditStep implements SagaStep<MutableAccount> {
        private String accountId;
        private int amount;

        public MutableCreditStep() {} // Jackson
        public MutableCreditStep(String accountId, int amount) {
            this.accountId = accountId;
            this.amount = amount;
        }

        public String getStepId() {
            return "credit-" + accountId;
        }

        public String getEntityKey() {
            return accountId;
        }

        public MutableAccount execute(MutableAccount entity) {
            if (entity.id.startsWith("fail"))
                throw new RuntimeException("Simulated Failure");
            entity.balance += amount;
            return entity;
        }

        public void compensate(MutableAccount entity, MutableAccount previousState) {
            entity.balance -= amount;
        }

        public String getAccountId() { return accountId; }
        public int getAmount() { return amount; }
    }
}