package com.stripeclone.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends an event. Called from inside whatever transaction is doing the real work, so
     * the event only exists if that work commits.
     */
    public void append(String eventId, String eventType, String aggregateId, String payload) {
        jdbc.sql("""
                INSERT INTO outbox (event_id, event_type, aggregate_id, payload)
                VALUES (:eventId, :eventType, :aggregateId, :payload)
                """)
                .param("eventId", eventId)
                .param("eventType", eventType)
                .param("aggregateId", aggregateId)
                .param("payload", payload)
                .update();
    }

    /**
     * Claims a batch of due events for this poller instance.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes more than one poller safe. Each one
     * takes rows nobody else has locked and skips past the rest rather than queuing behind
     * them, so two instances split the work instead of serialising on it.
     */
    public List<OutboxEvent> claimBatch(int batchSize) {
        return jdbc.sql("""
                SELECT id, event_id, event_type, aggregate_id, payload, status,
                       attempts, last_error, created_at, next_attempt_at, published_at
                  FROM outbox
                 WHERE status = 'PENDING'
                   AND next_attempt_at <= now()
                 ORDER BY id
                 LIMIT :batchSize
                   FOR UPDATE SKIP LOCKED
                """)
                .param("batchSize", batchSize)
                .query(OutboxRepository::mapEvent)
                .list();
    }

    public void markPublished(long id) {
        jdbc.sql("""
                UPDATE outbox
                   SET status = 'PUBLISHED', published_at = now(), attempts = attempts + 1
                 WHERE id = :id
                """)
                .param("id", id)
                .update();
    }

    /** Records a failure and schedules the next attempt. */
    public void markRetry(long id, String error, Duration backoff) {
        jdbc.sql("""
                UPDATE outbox
                   SET attempts = attempts + 1,
                       last_error = :error,
                       next_attempt_at = now() + make_interval(secs => :backoffSeconds)
                 WHERE id = :id
                """)
                .param("id", id)
                .param("error", truncate(error))
                .param("backoffSeconds", (double) backoff.toMillis() / 1000.0)
                .update();
    }

    /** Gives up on an event after too many attempts. */
    public void markFailed(long id, String error) {
        jdbc.sql("""
                UPDATE outbox
                   SET status = 'FAILED', attempts = attempts + 1, last_error = :error
                 WHERE id = :id
                """)
                .param("id", id)
                .param("error", truncate(error))
                .update();
    }

    public Optional<OutboxEvent> find(String eventId) {
        return jdbc.sql("""
                SELECT id, event_id, event_type, aggregate_id, payload, status,
                       attempts, last_error, created_at, next_attempt_at, published_at
                  FROM outbox
                 WHERE event_id = :eventId
                """)
                .param("eventId", eventId)
                .query(OutboxRepository::mapEvent)
                .optional();
    }

    public List<OutboxEvent> findByAggregate(String aggregateId) {
        return jdbc.sql("""
                SELECT id, event_id, event_type, aggregate_id, payload, status,
                       attempts, last_error, created_at, next_attempt_at, published_at
                  FROM outbox
                 WHERE aggregate_id = :aggregateId
                 ORDER BY id
                """)
                .param("aggregateId", aggregateId)
                .query(OutboxRepository::mapEvent)
                .list();
    }

    public List<OutboxEvent> listRecent(int limit) {
        return jdbc.sql("""
                SELECT id, event_id, event_type, aggregate_id, payload, status,
                       attempts, last_error, created_at, next_attempt_at, published_at
                  FROM outbox
                 ORDER BY id DESC
                 LIMIT :limit
                """)
                .param("limit", limit)
                .query(OutboxRepository::mapEvent)
                .list();
    }

    public long countPending() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE status = 'PENDING'")
                .query(Long.class)
                .single();
    }

    public long countByStatus(OutboxEvent.Status status) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE status = :status")
                .param("status", status.name())
                .query(Long.class)
                .single();
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }

    private static OutboxEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new OutboxEvent(
                rs.getLong("id"),
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getString("aggregate_id"),
                rs.getString("payload"),
                OutboxEvent.Status.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("next_attempt_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant());
    }

    /** Only used by tests, to simulate a poller that died before marking a row. */
    public void resetForRedelivery(long id) {
        jdbc.sql("""
                UPDATE outbox
                   SET status = 'PENDING', published_at = NULL, next_attempt_at = now()
                 WHERE id = :id
                """)
                .param("id", id)
                .update();
    }
}
