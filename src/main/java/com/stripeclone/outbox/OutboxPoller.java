package com.stripeclone.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Drains the outbox.
 *
 * <p>Runs on a timer, claims a batch, hands each event to whichever handlers want it, and
 * marks the row published if they all succeed. A handler that throws gets the event again
 * later, with the delay doubling each time.
 *
 * <p>The claim and the delivery share a transaction. That means a row stays locked while
 * it's being delivered, so a second poller can't pick it up at the same time, and a crash
 * mid-delivery rolls the claim back so the event is retried rather than lost. The cost is
 * that a slow handler holds a row lock, which is why {@code batchSize} is small and
 * handlers are expected to be quick.
 *
 * <p>What this gives you is at-least-once. A crash after the handler succeeded but before
 * the commit means the event is delivered again. That's unavoidable without distributed
 * transactions, so handlers dedupe on event id instead.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private static final long MAX_BACKOFF_SECONDS = 3600;

    private final OutboxRepository repository;
    private final List<EventHandler> handlers;

    @Value("${outbox.batch-size:50}")
    private int batchSize;

    @Value("${outbox.max-attempts:8}")
    private int maxAttempts;

    @Value("${outbox.base-backoff-seconds:2}")
    private long baseBackoffSeconds;

    public OutboxPoller(OutboxRepository repository, List<EventHandler> handlers) {
        this.repository = repository;
        this.handlers = handlers;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    public void poll() {
        try {
            drainOnce();
        } catch (Exception e) {
            // Never let the scheduler's thread die on an unexpected error.
            log.error("Outbox poll failed", e);
        }
    }

    /**
     * Processes one batch. Returns how many events were delivered.
     *
     * <p>Exposed so tests can drive the poller directly instead of waiting on the timer.
     */
    @Transactional
    public int drainOnce() {
        List<OutboxEvent> batch = repository.claimBatch(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        int delivered = 0;
        for (OutboxEvent event : batch) {
            if (deliver(event)) {
                delivered++;
            }
        }

        if (delivered > 0) {
            log.debug("Delivered {} of {} claimed events", delivered, batch.size());
        }
        return delivered;
    }

    private boolean deliver(OutboxEvent event) {
        try {
            for (EventHandler handler : handlers) {
                if (handler.handles(event.eventType())) {
                    handler.handle(event);
                }
            }
            repository.markPublished(event.id());
            return true;

        } catch (Exception e) {
            int attemptsSoFar = event.attempts() + 1;

            if (attemptsSoFar >= maxAttempts) {
                log.error("Giving up on event {} after {} attempts",
                        event.eventId(), attemptsSoFar, e);
                repository.markFailed(event.id(), e.toString());
            } else {
                Duration backoff = backoffFor(attemptsSoFar);
                log.warn("Delivery of {} failed on attempt {}, retrying in {}s: {}",
                        event.eventId(), attemptsSoFar, backoff.toSeconds(), e.toString());
                repository.markRetry(event.id(), e.toString(), backoff);
            }
            return false;
        }
    }

    /**
     * Doubling backoff, capped at an hour.
     *
     * <p>The shift is bounded at 40 only to keep it from overflowing a long on a
     * pathological attempt count; the real limit is the hour cap below it.
     */
    Duration backoffFor(int attempt) {
        long seconds = baseBackoffSeconds * (1L << Math.min(attempt - 1, 40));
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF_SECONDS));
    }

    /**
     * Delivers one batch in its own transaction, for tests that need the commit to happen
     * before they assert on it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int drainOnceInNewTransaction() {
        return drainOnce();
    }
}
