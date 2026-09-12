package com.stripeclone.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes a write happen at most once per idempotency key.
 *
 * <p>The guarantee rests on one unique constraint. A request inserts its key; if the
 * insert succeeds it owns the work, and if it fails on the constraint then someone else
 * got there first and this request replays their answer instead. There is no window
 * between checking and claiming, because the check <em>is</em> the claim.
 *
 * <p>The key record and the business write share one database transaction, so a rolled-back
 * write takes its key with it and the caller can retry. A design that stored keys in a
 * cache instead has no way to make that true.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs {@code work} at most once for the given key.
     *
     * <p>On a repeat call with the same key and the same body, the stored response is
     * returned and {@code work} does not run.
     *
     * @param idempotencyKey the caller's key; when null the work simply runs unprotected
     * @param endpoint       which operation the key belongs to, for diagnostics
     * @param requestBody    hashed so a key reused with a different body is rejected
     * @param responseType   type to deserialise a replayed response into
     * @param work           the operation to perform, returning the response to store
     * @throws IdempotencyConflictException if the key was used with a different body
     * @throws ConcurrentRequestException   if an earlier request with this key is still running
     */
    @Transactional
    public <T> Result<T> execute(
            String idempotencyKey,
            String endpoint,
            Object requestBody,
            Class<T> responseType,
            Supplier<Outcome<T>> work) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            Outcome<T> outcome = work.get();
            return new Result<>(outcome.response(), outcome.httpStatus(), false);
        }

        String requestHash = hash(requestBody);

        if (repository.tryClaim(idempotencyKey, requestHash, endpoint)) {
            Outcome<T> outcome = work.get();
            repository.complete(
                    idempotencyKey,
                    outcome.httpStatus(),
                    serialise(outcome.response()),
                    outcome.resourceId());

            log.debug("Completed first request for idempotency key {}", idempotencyKey);
            return new Result<>(outcome.response(), outcome.httpStatus(), false);
        }

        // Lost the race. Lock the existing row, which blocks until the winner commits.
        IdempotencyRecord existing = repository.findForUpdate(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency key " + idempotencyKey + " vanished after a claim conflict"));

        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }

        if (!existing.isCompleted()) {
            // The winner is still running, in another transaction we cannot see.
            throw new ConcurrentRequestException(idempotencyKey);
        }

        log.debug("Replaying stored response for idempotency key {}", idempotencyKey);
        return new Result<>(
                deserialise(existing.responseBody(), responseType),
                existing.responseStatus(),
                true);
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> find(String idempotencyKey) {
        return repository.find(idempotencyKey);
    }

    /**
     * Releases a claim in its own transaction, so the release survives the rollback of the
     * transaction whose work failed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseClaim(String idempotencyKey) {
        repository.release(idempotencyKey);
    }

    /**
     * SHA-256 of the canonical JSON form of the request.
     *
     * <p>Serialising through Jackson with sorted map keys means two bodies that differ only
     * in field order or whitespace hash the same, so a client retrying the identical
     * request is never rejected over formatting.
     */
    String hash(Object requestBody) {
        if (requestBody == null) {
            return "null";
        }
        try {
            byte[] canonical = objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(requestBody);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Request body is not serialisable", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String serialise(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Response is not serialisable", e);
        }
    }

    private <T> T deserialise(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body.getBytes(StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            throw new IllegalStateException("Stored response could not be read back", e);
        }
    }

    /** What the protected work produced, plus how it should be reported over HTTP. */
    public record Outcome<T>(T response, int httpStatus, String resourceId) {

        public static <T> Outcome<T> created(T response, String resourceId) {
            return new Outcome<>(response, 201, resourceId);
        }

        public static <T> Outcome<T> ok(T response, String resourceId) {
            return new Outcome<>(response, 200, resourceId);
        }
    }

    /** The response, and whether it came from a stored record rather than fresh work. */
    public record Result<T>(T response, int httpStatus, boolean replayed) {
    }
}
