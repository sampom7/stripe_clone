package com.stripeclone.webhook;

import java.time.Instant;

public record WebhookDelivery(
        long id,
        String deliveryId,
        String endpointId,
        String eventId,
        String eventType,
        String payload,
        Status status,
        int attempts,
        Integer responseStatus,
        String lastError,
        Instant createdAt,
        Instant nextAttemptAt,
        Instant deliveredAt
) {
    public enum Status {
        PENDING,
        DELIVERED,
        /** Gave up. The endpoint owner can replay it from /v1/events. */
        FAILED
    }
}
