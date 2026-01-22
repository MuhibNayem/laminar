package com.nayem.laminar.dlq;

import com.nayem.laminar.core.Mutation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Interface for a Dead-Letter Queue (DLQ) that stores mutations that failed
 * processing.
 * <p>
 * Implementations should ensure durability and thread-safety.
 * </p>
 *
 * @param <T> The entity type for mutations
 */
public interface DeadLetterQueue<T> {

    /**
     * Sends a failed mutation to the dead-letter queue.
     *
     * @param entry The DLQ entry containing the mutation and failure metadata
     */
    void send(DlqEntry<T> entry);

    /**
     * Peeks at the next entry in the queue without removing it.
     *
     * @return An optional containing the next entry, or empty if the queue is empty
     */
    Optional<DlqEntry<T>> peek();

    /**
     * Retrieves and removes the next entry from the queue.
     *
     * @return An optional containing the next entry, or empty if the queue is empty
     */
    Optional<DlqEntry<T>> poll();

    /**
     * Acknowledges successful reprocessing of an entry, removing it from the queue.
     *
     * @param entryId The unique ID of the entry to acknowledge
     */
    void acknowledge(String entryId);

    /**
     * Returns the current size of the dead-letter queue.
     *
     * @return The number of entries in the queue
     */
    long size();

    /**
     * Lists all entries in the queue (for monitoring/admin purposes).
     *
     * @param limit Maximum number of entries to return
     * @return A list of DLQ entries
     */
    List<DlqEntry<T>> list(int limit);

    /**
     * Represents an entry in the dead-letter queue.
     *
     * @param <T> The entity type
     */
    record DlqEntry<T>(
            String id,
            Mutation<T> mutation,
            String entityKey,
            String errorMessage,
            String errorClass,
            int retryCount,
            Instant timestamp,
            Instant lastRetryTimestamp) {
        public DlqEntry<T> withIncrementedRetry() {
            return new DlqEntry<>(id, mutation, entityKey, errorMessage, errorClass,
                    retryCount + 1, timestamp, Instant.now());
        }
    }
}
