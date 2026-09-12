package com.stripeclone.idempotency;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Attempts to claim a key by inserting an IN_PROGRESS row.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than catching the unique violation, because
     * in Postgres a constraint violation aborts the entire transaction: every statement
     * afterwards fails with "current transaction is aborted" regardless of the exception
     * being handled in Java. Since the caller must go on to read the existing record in
     * this same transaction, the conflict has to be absorbed by the database rather than
     * raised at all.
     *
     * @return true if this caller won the claim, false if the key already existed
     */
    public boolean tryClaim(String idempotencyKey, String requestHash, String endpoint) {
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_keys
                       (idempotency_key, request_hash, endpoint, status)
                VALUES (:key, :hash, :endpoint, 'IN_PROGRESS')
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("endpoint", endpoint)
                .update();

        return inserted == 1;
    }

    public Optional<IdempotencyRecord> find(String idempotencyKey) {
        return jdbc.sql("""
                SELECT idempotency_key, request_hash, endpoint, status,
                       response_status, response_body, resource_id,
                       created_at, completed_at
                  FROM idempotency_keys
                 WHERE idempotency_key = :key
                """)
                .param("key", idempotencyKey)
                .query(IdempotencyRepository::mapRecord)
                .optional();
    }

    /**
     * Reads the record and takes a row lock on it.
     *
     * <p>Used by a caller that lost the insert race, so that it waits for the winner's
     * transaction to finish rather than reading a half-written IN_PROGRESS row.
     */
    public Optional<IdempotencyRecord> findForUpdate(String idempotencyKey) {
        return jdbc.sql("""
                SELECT idempotency_key, request_hash, endpoint, status,
                       response_status, response_body, resource_id,
                       created_at, completed_at
                  FROM idempotency_keys
                 WHERE idempotency_key = :key
                   FOR UPDATE
                """)
                .param("key", idempotencyKey)
                .query(IdempotencyRepository::mapRecord)
                .optional();
    }

    public void complete(
            String idempotencyKey, int responseStatus, String responseBody, String resourceId) {

        jdbc.sql("""
                UPDATE idempotency_keys
                   SET status = 'COMPLETED',
                       response_status = :responseStatus,
                       response_body = :responseBody,
                       resource_id = :resourceId,
                       completed_at = now()
                 WHERE idempotency_key = :key
                """)
                .param("key", idempotencyKey)
                .param("responseStatus", responseStatus)
                .param("responseBody", responseBody)
                .param("resourceId", resourceId)
                .update();
    }

    /** Releases a claim whose work failed, so the caller may retry with the same key. */
    public void release(String idempotencyKey) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE idempotency_key = :key AND status = 'IN_PROGRESS'")
                .param("key", idempotencyKey)
                .update();
    }

    private static IdempotencyRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        Integer responseStatus = rs.getObject("response_status", Integer.class);

        return new IdempotencyRecord(
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getString("endpoint"),
                IdempotencyRecord.Status.valueOf(rs.getString("status")),
                responseStatus,
                rs.getString("response_body"),
                rs.getString("resource_id"),
                rs.getTimestamp("created_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant());
    }
}
