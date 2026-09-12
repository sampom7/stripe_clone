package com.stripeclone.outbox;

import java.time.Instant;

public record OutboxEvent(
        long id,
        String eventId,
        String eventType,
        String aggregateId,
        String payload,
        Status status,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant nextAttemptAt,
        Instant publishedAt
) {
    public enum Status {
        PENDING,
        PUBLISHED,
        /** Gave up after too many attempts. Needs someone to look at it. */
        FAILED
    }
}
