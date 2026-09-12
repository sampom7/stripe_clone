package com.stripeclone.webhook;

import com.stripeclone.outbox.EventHandler;
import com.stripeclone.outbox.OutboxEvent;
import org.springframework.stereotype.Component;

/**
 * Turns an outbox event into one queued delivery per interested endpoint.
 *
 * <p>This is the join between the two halves. The outbox poller calls this; it writes
 * delivery rows and returns. Nothing goes over the network here, because the outbox poller
 * holds a row lock while a handler runs, and an unreachable endpoint would hold that lock
 * for the length of a timeout. Actual sending is the delivery poller's job.
 *
 * <p>Queueing is idempotent thanks to the unique constraint on (endpoint, event), which
 * matters because the outbox is at-least-once and this handler will see some events twice.
 */
@Component
public class WebhookFanOutHandler implements EventHandler {

    private final WebhookService webhooks;

    public WebhookFanOutHandler(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @Override
    public void handle(OutboxEvent event) {
        webhooks.fanOut(event.eventId(), event.eventType(), event.payload());
    }
}
