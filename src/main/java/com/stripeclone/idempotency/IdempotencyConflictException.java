package com.stripeclone.idempotency;

/**
 * Thrown when an idempotency key is reused with a different request body.
 *
 * <p>Replaying the stored response would answer a question the caller did not ask, and
 * executing the new body would break the promise the key makes. Stripe returns an error
 * here; so does this. Maps to HTTP 422.
 */
public class IdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key " + idempotencyKey
                + " was already used with a different request body");
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
