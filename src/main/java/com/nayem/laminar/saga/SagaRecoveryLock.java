package com.nayem.laminar.saga;

/**
 * Distributed lock interface for saga recovery coordination.
 */
public interface SagaRecoveryLock {

    /**
     * Attempts to acquire a distributed lock.
     *
     * @param lockKey        The unique key for the lock
     * @param lockDurationMs How long to hold the lock in milliseconds
     * @return true if lock was acquired, false otherwise
     */
    boolean acquireLock(String lockKey, long lockDurationMs);

    /**
     * Releases the lock.
     *
     * @param lockKey The key to unlock
     */
    void releaseLock(String lockKey);
}