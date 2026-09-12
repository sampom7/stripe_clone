package com.stripeclone.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripeclone.common.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Writes events to the outbox.
 *
 * <p>Always called from inside the transaction doing the actual work. That's the whole
 * point: if the ledger write rolls back, the event insert rolls back with it and nothing
 * was ever announced.
 */
@Service
public class OutboxService {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Queues an event for delivery.
     *
     * <p>Deliberately not annotated with {@code REQUIRES_NEW}: it has to join the caller's
     * transaction, not start its own.
     */
    @Transactional
    public String publish(String eventType, String aggregateId, Object payload) {
        String eventId = Ids.event();
        repository.append(eventId, eventType, aggregateId, serialise(payload));
        return eventId;
    }

    @Transactional(readOnly = true)
    public Optional<OutboxEvent> find(String eventId) {
        return repository.find(eventId);
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> findByAggregate(String aggregateId) {
        return repository.findByAggregate(aggregateId);
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> listRecent(int limit) {
        return repository.listRecent(limit);
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return repository.countPending();
    }

    private String serialise(Object payload) {
        if (payload instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event payload is not serialisable", e);
        }
    }
}
