package com.nayem.laminar.saga;

public class NoOpSagaRecoveryLock implements SagaRecoveryLock {
    @Override
    public boolean acquireLock(String lockKey, long lockDurationMs) {
        return true;
    }

    @Override
    public void releaseLock(String lockKey) {
    }
}