package com.nayem.laminar.saga;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SagaBuilderTest {

    @Test
    void testBuilderCreatesValidSaga() {
        LaminarSaga<TestAccount> saga = SagaBuilder.<TestAccount>newSaga("transfer-123", TestAccount.class)
                .description("Test transfer")
                .step(FunctionalSagaStep.<TestAccount>builder()
                        .stepId("step-1")
                        .entityKey("acc-1")
                        .execute(a -> a)
                        .build())
                .build();

        assertEquals("transfer-123", saga.getSagaId());
        assertEquals(TestAccount.class, saga.getEntityType());
        assertEquals(1, saga.getSteps().size());
        assertEquals("Test transfer", saga.getDescription());
    }

    @Test
    void testMultipleStepsPreserveOrder() {
        LaminarSaga<TestAccount> saga = SagaBuilder.<TestAccount>newSaga("multi-step", TestAccount.class)
                .step(FunctionalSagaStep.<TestAccount>builder()
                        .stepId("first")
                        .entityKey("acc")
                        .execute(a -> a)
                        .build())
                .step(FunctionalSagaStep.<TestAccount>builder()
                        .stepId("second")
                        .entityKey("acc")
                        .execute(a -> a)
                        .build())
                .step(FunctionalSagaStep.<TestAccount>builder()
                        .stepId("third")
                        .entityKey("acc")
                        .execute(a -> a)
                        .build())
                .build();

        assertEquals(3, saga.getSteps().size());
        assertEquals("first", saga.getSteps().get(0).getStepId());
        assertEquals("second", saga.getSteps().get(1).getStepId());
        assertEquals("third", saga.getSteps().get(2).getStepId());
    }

    @Test
    void testOnSuccessCallbackIsInvoked() {
        AtomicBoolean successCalled = new AtomicBoolean(false);

        LaminarSaga<TestAccount> saga = SagaBuilder.<TestAccount>newSaga("success-test", TestAccount.class)
                .step(FunctionalSagaStep.<TestAccount>builder()
                        .stepId("step")
                        .entityKey("acc")
                        .execute(a -> a)
                        .build())
                .onSuccess(() -> successCalled.set(true))
                .build();

        saga.onSuccess();
        assertTrue(successCalled.get());
    }

    @Test
    void testOnFailureCallbackIsInvoked() {
        AtomicBoolean failureCalled = new AtomicBoolean(false);

        LaminarSaga<TestAccount> saga = SagaBuilder.<TestAccount>newSaga("failure-test", TestAccount.class)
                .step(FunctionalSagaStep.<TestAccount>builder()
                        .stepId("step")
                        .entityKey("acc")
                        .execute(a -> a)
                        .build())
                .onFailure(e -> failureCalled.set(true))
                .build();

        saga.onFailure(new RuntimeException("Test"));
        assertTrue(failureCalled.get());
    }

    @Test
    void testBuilderFailsWithoutSteps() {
        assertThrows(IllegalStateException.class,
                () -> SagaBuilder.<TestAccount>newSaga("empty", TestAccount.class).build());
    }

    @Test
    void testDefaultDescriptionUsesSagaId() {
        LaminarSaga<TestAccount> saga = SagaBuilder.<TestAccount>newSaga("my-saga", TestAccount.class)
                .step(FunctionalSagaStep.<TestAccount>builder()
                        .stepId("step")
                        .entityKey("acc")
                        .execute(a -> a)
                        .build())
                .build();

        assertEquals("Saga-my-saga", saga.getDescription());
    }

    static class TestAccount {
        BigDecimal balance = BigDecimal.ZERO;
    }
}
