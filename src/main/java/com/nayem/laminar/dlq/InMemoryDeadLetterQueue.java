package com.nayem.laminar.dlq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-memory implementation of {@link DeadLetterQueue}.
 * <p>
 * Suitable for development, testing, and single-instance deployments.
 * Note: Entries are lost on application restart.
 * </p>
 *
 * @param <T> The entity type
 */
public class InMemoryDeadLetterQueue<T> implements DeadLetterQueue<T> {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDeadLetterQueue.class);

    private final Queue<DlqEntry<T>> queue = new ConcurrentLinkedQueue<>();
    private final Map<String, DlqEntry<T>> entriesById = new ConcurrentHashMap<>();

    @Override
    public void send(DlqEntry<T> entry) {
        queue.offer(entry);
        entriesById.put(entry.id(), entry);
        log.warn("Mutation sent to DLQ: entityKey={}, error={}, retryCount={}",
                entry.entityKey(), entry.errorMessage(), entry.retryCount());
    }

    @Override
    public Optional<DlqEntry<T>> peek() {
        return Optional.ofNullable(queue.peek());
    }

    @Override
    public Optional<DlqEntry<T>> poll() {
        DlqEntry<T> entry = queue.poll();
        if (entry != null) {
            entriesById.remove(entry.id());
        }
        return Optional.ofNullable(entry);
    }

    @Override
    public void acknowledge(String entryId) {
        DlqEntry<T> removed = entriesById.remove(entryId);
        if (removed != null) {
            queue.remove(removed);
            log.info("DLQ entry acknowledged and removed: id={}", entryId);
        }
    }

    @Override
    public long size() {
        return queue.size();
    }

    @Override
    public List<DlqEntry<T>> list(int limit) {
        return queue.stream().limit(limit).toList();
    }

    /**
     * Clears all entries from the queue (for testing).
     */
    public void clear() {
        queue.clear();
        entriesById.clear();
    }
}
