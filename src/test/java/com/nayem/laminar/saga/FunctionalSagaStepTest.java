package com.nayem.laminar.saga;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FunctionalSagaStepTest {

    @Test
    void testBuilderCreatesValidStep() {
        FunctionalSagaStep<TestAccount> step = FunctionalSagaStep.<TestAccount>builder()
                .stepId("debit-123")
                .entityKey("account-123")
                .execute(account -> {
                    account.balance = account.balance.subtract(BigDecimal.TEN);
                    return account;
                })
                .compensate((account, prev) -> {
                    account.balance = prev.balance;
                })
                .build();

        assertEquals("debit-123", step.getStepId());
        assertEquals("account-123", step.getEntityKey());
        assertTrue(step.isIdempotent());
        assertEquals(3, step.maxRetries());
    }

    @Test
    void testExecuteDelegatesToLambda() {
        FunctionalSagaStep<TestAccount> step = FunctionalSagaStep.<TestAccount>builder()
                .stepId("debit")
                .entityKey("acc")
                .execute(account -> {
                    account.balance = account.balance.subtract(new BigDecimal("50"));
                    return account;
                })
                .build();

        TestAccount account = new TestAccount(new BigDecimal("100"));
        TestAccount result = step.execute(account);

        assertEquals(new BigDecimal("50"), result.balance);
    }

    @Test
    void testCompensateDelegatesToLambda() {
        AtomicReference<TestAccount> compensatedWith = new AtomicReference<>();

        FunctionalSagaStep<TestAccount> step = FunctionalSagaStep.<TestAccount>builder()
                .stepId("debit")
                .entityKey("acc")
                .execute(account -> account)
                .compensate((current, prev) -> {
                    compensatedWith.set(prev);
                    current.balance = prev.balance;
                })
                .build();

        TestAccount current = new TestAccount(new BigDecimal("50"));
        TestAccount previous = new TestAccount(new BigDecimal("100"));

        step.compensate(current, previous);

        assertSame(previous, compensatedWith.get());
        assertEquals(new BigDecimal("100"), current.balance);
    }

    @Test
    void testBuilderFailsWithoutRequiredFields() {
        assertThrows(IllegalStateException.class, () -> FunctionalSagaStep.<TestAccount>builder()
                .entityKey("acc")
                .execute(a -> a)
                .build());

        assertThrows(IllegalStateException.class, () -> FunctionalSagaStep.<TestAccount>builder()
                .stepId("step")
                .execute(a -> a)
                .build());

        assertThrows(IllegalStateException.class, () -> FunctionalSagaStep.<TestAccount>builder()
                .stepId("step")
                .entityKey("acc")
                .build());
    }

    @Test
    void testCustomIdempotencyAndRetries() {
        FunctionalSagaStep<TestAccount> step = FunctionalSagaStep.<TestAccount>builder()
                .stepId("risky-step")
                .entityKey("acc")
                .execute(a -> a)
                .idempotent(false)
                .maxRetries(5)
                .build();

        assertFalse(step.isIdempotent());
        assertEquals(5, step.maxRetries());
    }

    static class TestAccount {
        BigDecimal balance;

        TestAccount(BigDecimal balance) {
            this.balance = balance;
        }
    }
}
