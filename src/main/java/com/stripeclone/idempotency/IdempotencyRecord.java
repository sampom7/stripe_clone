package com.stripeclone.idempotency;

import java.time.Instant;

/** A stored idempotency key and, once the work finishes, the response to replay. */
public record IdempotencyRecord(
        String idempotencyKey,
        String requestHash,
        String endpoint,
        Status status,
        Integer responseStatus,
        String responseBody,
        String resourceId,
        Instant createdAt,
        Instant completedAt
) {
    public enum Status {
        /** Claimed by a request that has not finished yet. */
        IN_PROGRESS,
        /** Finished; responseStatus and responseBody hold what to replay. */
        COMPLETED
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }
}
