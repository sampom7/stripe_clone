package com.stripeclone.outbox;

/**
 * Something that wants outbox events delivered to it.
 *
 * <p>Delivery is at-least-once, so a handler has to tolerate seeing the same event twice.
 * Dedupe on {@code eventId} if that matters.
 *
 * <p>Throwing schedules a retry. Returning normally marks the event published.
 */
public interface EventHandler {

    void handle(OutboxEvent event) throws Exception;

    /** Whether this handler wants a given event type. */
    default boolean handles(String eventType) {
        return true;
    }
}
