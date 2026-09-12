package com.stripeclone.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sends queued webhooks, retrying the ones that fail.
 *
 * <p>Separate from the outbox poller on purpose. The outbox is about getting events out of
 * the database safely and should never block on anything external; this is about talking to
 * endpoints that may be slow, down, or gone. Mixing the two would let one broken receiver
 * hold up event processing for everything else.
 *
 * <p>Backoff doubles from a minute, capped at a day, which is roughly Stripe's schedule.
 * The long tail matters: an endpoint that is down for an afternoon should still get its
 * events when it comes back, without being hammered in the meantime.
 */
@Component
public class WebhookDeliveryPoller {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryPoller.class);

    private static final long MAX_BACKOFF_SECONDS = 86_400;

    private final WebhookRepository repository;
    private final WebhookSender sender;

    @Value("${webhook.batch-size:20}")
    private int batchSize;

    @Value("${webhook.max-attempts:8}")
    private int maxAttempts;

    @Value("${webhook.base-backoff-seconds:60}")
    private long baseBackoffSeconds;

    public WebhookDeliveryPoller(WebhookRepository repository, WebhookSender sender) {
        this.repository = repository;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${webhook.poll-interval-ms:2000}")
    public void poll() {
        try {
            deliverOnce();
        } catch (Exception e) {
            log.error("Webhook delivery poll failed", e);
        }
    }

    /** Sends one batch. Returns how many went through. */
    @Transactional
    public int deliverOnce() {
        List<WebhookDelivery> batch = repository.claimDue(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        int delivered = 0;
        for (WebhookDelivery delivery : batch) {
            if (attempt(delivery)) {
                delivered++;
            }
        }
        return delivered;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deliverOnceInNewTransaction() {
        return deliverOnce();
    }

    private boolean attempt(WebhookDelivery delivery) {
        WebhookEndpoint endpoint = repository.findEndpoint(delivery.endpointId()).orElse(null);
        if (endpoint == null) {
            repository.markFailed(delivery.id(), null, "Endpoint no longer exists");
            return false;
        }

        String signature = WebhookSignature.sign(
                delivery.payload(), endpoint.secret(), Instant.now());

        WebhookSender.Result result = sender.send(
                endpoint.url(), delivery.payload(), signature, delivery.eventId());

        if (result.isSuccess()) {
            repository.markDelivered(delivery.id(), result.statusCode());
            return true;
        }

        int attemptsSoFar = delivery.attempts() + 1;
        if (attemptsSoFar >= maxAttempts) {
            log.warn("Giving up on delivery {} to {} after {} attempts",
                    delivery.deliveryId(), endpoint.url(), attemptsSoFar);
            repository.markFailed(delivery.id(), result.statusCode(), result.error());
        } else {
            repository.markRetry(delivery.id(), result.statusCode(), result.error(),
                    backoffFor(attemptsSoFar));
        }
        return false;
    }

    Duration backoffFor(int attempt) {
        long seconds = baseBackoffSeconds * (1L << Math.min(attempt - 1, 40));
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF_SECONDS));
    }
}
