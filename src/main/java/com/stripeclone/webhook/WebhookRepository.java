package com.stripeclone.webhook;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class WebhookRepository {

    private final JdbcClient jdbc;

    public WebhookRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------- endpoints

    public void insertEndpoint(WebhookEndpoint endpoint) {
        jdbc.sql("""
                INSERT INTO webhook_endpoints
                       (endpoint_id, url, secret, description, enabled_events, status)
                VALUES (:id, :url, :secret, :description, :events, :status)
                """)
                .param("id", endpoint.endpointId())
                .param("url", endpoint.url())
                .param("secret", endpoint.secret())
                .param("description", endpoint.description())
                .param("events", String.join(",", endpoint.enabledEvents()))
                .param("status", endpoint.status().wireValue())
                .update();
    }

    public Optional<WebhookEndpoint> findEndpoint(String endpointId) {
        return jdbc.sql(SELECT_ENDPOINT + " WHERE endpoint_id = :id")
                .param("id", endpointId)
                .query(WebhookRepository::mapEndpoint)
                .optional();
    }

    public List<WebhookEndpoint> findEnabledEndpoints() {
        return jdbc.sql(SELECT_ENDPOINT + " WHERE status = 'enabled' ORDER BY id")
                .query(WebhookRepository::mapEndpoint)
                .list();
    }

    public List<WebhookEndpoint> listEndpoints(int limit) {
        return jdbc.sql(SELECT_ENDPOINT + " ORDER BY id DESC LIMIT :limit")
                .param("limit", limit)
                .query(WebhookRepository::mapEndpoint)
                .list();
    }

    public void setEndpointStatus(String endpointId, WebhookEndpoint.Status status) {
        jdbc.sql("UPDATE webhook_endpoints SET status = :status WHERE endpoint_id = :id")
                .param("id", endpointId)
                .param("status", status.wireValue())
                .update();
    }

    public void deleteEndpoint(String endpointId) {
        jdbc.sql("DELETE FROM webhook_deliveries WHERE endpoint_id = :id")
                .param("id", endpointId)
                .update();
        jdbc.sql("DELETE FROM webhook_endpoints WHERE endpoint_id = :id")
                .param("id", endpointId)
                .update();
    }

    // ------------------------------------------------------------- deliveries

    /**
     * Queues a delivery, ignoring it if one already exists for this endpoint and event.
     *
     * <p>The unique constraint is what makes the fan-out safe to repeat: the outbox is
     * at-least-once, so this method will be called twice for some events, and the second
     * call has to be a no-op rather than a duplicate webhook.
     */
    public boolean queueDelivery(
            String deliveryId, String endpointId, String eventId,
            String eventType, String payload) {

        int inserted = jdbc.sql("""
                INSERT INTO webhook_deliveries
                       (delivery_id, endpoint_id, event_id, event_type, payload)
                VALUES (:deliveryId, :endpointId, :eventId, :eventType, :payload)
                ON CONFLICT (endpoint_id, event_id) DO NOTHING
                """)
                .param("deliveryId", deliveryId)
                .param("endpointId", endpointId)
                .param("eventId", eventId)
                .param("eventType", eventType)
                .param("payload", payload)
                .update();

        return inserted == 1;
    }

    public List<WebhookDelivery> claimDue(int batchSize) {
        return jdbc.sql(SELECT_DELIVERY + """
                 WHERE status = 'PENDING'
                   AND next_attempt_at <= now()
                 ORDER BY id
                 LIMIT :batchSize
                   FOR UPDATE SKIP LOCKED
                """)
                .param("batchSize", batchSize)
                .query(WebhookRepository::mapDelivery)
                .list();
    }

    public void markDelivered(long id, int responseStatus) {
        jdbc.sql("""
                UPDATE webhook_deliveries
                   SET status = 'DELIVERED',
                       delivered_at = now(),
                       attempts = attempts + 1,
                       response_status = :responseStatus
                 WHERE id = :id
                """)
                .param("id", id)
                .param("responseStatus", responseStatus)
                .update();
    }

    public void markRetry(long id, Integer responseStatus, String error, Duration backoff) {
        jdbc.sql("""
                UPDATE webhook_deliveries
                   SET attempts = attempts + 1,
                       response_status = :responseStatus,
                       last_error = :error,
                       next_attempt_at = now() + make_interval(secs => :backoffSeconds)
                 WHERE id = :id
                """)
                .param("id", id)
                .param("responseStatus", responseStatus)
                .param("error", truncate(error))
                .param("backoffSeconds", (double) backoff.toMillis() / 1000.0)
                .update();
    }

    public void markFailed(long id, Integer responseStatus, String error) {
        jdbc.sql("""
                UPDATE webhook_deliveries
                   SET status = 'FAILED',
                       attempts = attempts + 1,
                       response_status = :responseStatus,
                       last_error = :error
                 WHERE id = :id
                """)
                .param("id", id)
                .param("responseStatus", responseStatus)
                .param("error", truncate(error))
                .update();
    }

    public Optional<WebhookDelivery> findDelivery(String deliveryId) {
        return jdbc.sql(SELECT_DELIVERY + " WHERE delivery_id = :id")
                .param("id", deliveryId)
                .query(WebhookRepository::mapDelivery)
                .optional();
    }

    public List<WebhookDelivery> findDeliveriesForEvent(String eventId) {
        return jdbc.sql(SELECT_DELIVERY + " WHERE event_id = :eventId ORDER BY id")
                .param("eventId", eventId)
                .query(WebhookRepository::mapDelivery)
                .list();
    }

    public List<WebhookDelivery> findDeliveriesForEndpoint(String endpointId, int limit) {
        return jdbc.sql(SELECT_DELIVERY
                        + " WHERE endpoint_id = :endpointId ORDER BY id DESC LIMIT :limit")
                .param("endpointId", endpointId)
                .param("limit", limit)
                .query(WebhookRepository::mapDelivery)
                .list();
    }

    public long countByStatus(WebhookDelivery.Status status) {
        return jdbc.sql("SELECT count(*) FROM webhook_deliveries WHERE status = :status")
                .param("status", status.name())
                .query(Long.class)
                .single();
    }

    /** Puts a failed delivery back in the queue. Used by the replay endpoint. */
    public void requeue(String deliveryId) {
        jdbc.sql("""
                UPDATE webhook_deliveries
                   SET status = 'PENDING', next_attempt_at = now(), attempts = 0,
                       last_error = NULL
                 WHERE delivery_id = :id
                """)
                .param("id", deliveryId)
                .update();
    }

    // ----------------------------------------------------------------- mapping

    private static final String SELECT_ENDPOINT = """
            SELECT endpoint_id, url, secret, description, enabled_events, status, created_at
              FROM webhook_endpoints
            """;

    private static final String SELECT_DELIVERY = """
            SELECT id, delivery_id, endpoint_id, event_id, event_type, payload, status,
                   attempts, response_status, last_error, created_at, next_attempt_at,
                   delivered_at
              FROM webhook_deliveries
            """;

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }

    private static WebhookEndpoint mapEndpoint(ResultSet rs, int rowNum) throws SQLException {
        Set<String> events = new LinkedHashSet<>(
                Arrays.asList(rs.getString("enabled_events").split(",")));

        return new WebhookEndpoint(
                rs.getString("endpoint_id"),
                rs.getString("url"),
                rs.getString("secret"),
                rs.getString("description"),
                events,
                WebhookEndpoint.Status.valueOf(rs.getString("status").toUpperCase()),
                rs.getTimestamp("created_at").toInstant());
    }

    private static WebhookDelivery mapDelivery(ResultSet rs, int rowNum) throws SQLException {
        Timestamp deliveredAt = rs.getTimestamp("delivered_at");

        return new WebhookDelivery(
                rs.getLong("id"),
                rs.getString("delivery_id"),
                rs.getString("endpoint_id"),
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                WebhookDelivery.Status.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getObject("response_status", Integer.class),
                rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("next_attempt_at").toInstant(),
                deliveredAt == null ? null : deliveredAt.toInstant());
    }
}
