package com.stripeclone.idempotency;

/**
 * Thrown when a request arrives while an earlier one holding the same key is still running.
 *
 * <p>Maps to HTTP 409. The caller should retry; by then the first request will have
 * completed and the response will be replayed from the stored record.
 */
public class ConcurrentRequestException extends RuntimeException {

    private final String idempotencyKey;

    public ConcurrentRequestException(String idempotencyKey) {
        super("A request with idempotency key " + idempotencyKey + " is already in progress");
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
