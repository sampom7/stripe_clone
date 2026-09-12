package com.stripeclone.outbox;

import com.stripeclone.PostgresTestBase;
import com.stripeclone.common.Ids;
import com.stripeclone.ledger.AccountType;
import com.stripeclone.ledger.LedgerService;
import com.stripeclone.ledger.TransactionKind;
import com.stripeclone.money.Amount;
import com.stripeclone.payment.CaptureMethod;
import com.stripeclone.payment.Customer;
import com.stripeclone.payment.PaymentIntent;
import com.stripeclone.payment.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.stripeclone.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(OutboxTest.RecordingHandlerConfig.class)
class OutboxTest extends PostgresTestBase {

    /**
     * A handler the tests can steer: it records what it sees and can be told to fail.
     */
    static class RecordingHandler implements EventHandler {
        final List<OutboxEvent> received = new ArrayList<>();
        final AtomicInteger failuresRemaining = new AtomicInteger(0);

        @Override
        public synchronized void handle(OutboxEvent event) throws Exception {
            if (failuresRemaining.get() > 0) {
                failuresRemaining.decrementAndGet();
                throw new IllegalStateException("handler asked to fail");
            }
            received.add(event);
        }

        synchronized void reset() {
            received.clear();
            failuresRemaining.set(0);
        }

        synchronized List<String> typesSeen() {
            return received.stream().map(OutboxEvent::eventType).toList();
        }
    }

    @TestConfiguration
    static class RecordingHandlerConfig {
        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }

    @Autowired
    OutboxService outbox;

    @Autowired
    OutboxRepository outboxRepository;

    @Autowired
    OutboxPoller poller;

    @Autowired
    PaymentService payments;

    @Autowired
    LedgerService ledger;

    @Autowired
    RecordingHandler handler;

    @Autowired
    TransactionTemplate transactions;

    private static final String EXTERNAL = "acct_external_usd";
    private Customer customer;
    private String merchantAccount;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE TABLE outbox RESTART IDENTITY").update();
        handler.reset();

        ledger.createAccount(EXTERNAL, AccountType.EXTERNAL, USD);
        customer = payments.createCustomer("buyer@example.com", "A Buyer", USD);
        ledger.transfer(Ids.transaction(), TransactionKind.FUNDING,
                EXTERNAL, customer.accountId(), Amount.of(100_000, USD), "funding");

        merchantAccount = Ids.account();
        ledger.createAccount(merchantAccount, AccountType.MERCHANT, USD);

        // Clear the events the setup itself produced.
        jdbc.sql("TRUNCATE TABLE outbox RESTART IDENTITY").update();
        handler.reset();
    }

    private PaymentIntent newIntent(Amount amount, CaptureMethod method) {
        return payments.createIntent(new PaymentService.CreateIntentRequest(
                customer.customerId(), customer.accountId(), merchantAccount,
                amount, method, "test"));
    }

    @Nested
    @DisplayName("writing to the outbox")
    class Writing {

        @Test
        void an_event_is_queued_pending() {
            String eventId = outbox.publish("test.event", "agg_1", Map.of("hello", "world"));

            OutboxEvent event = outbox.find(eventId).orElseThrow();
            assertThat(event.status()).isEqualTo(OutboxEvent.Status.PENDING);
            assertThat(event.eventType()).isEqualTo("test.event");
            assertThat(event.payload()).contains("world");
            assertThat(event.attempts()).isZero();
        }

        @Test
        void a_payment_produces_events_describing_it() {
            PaymentIntent intent = newIntent(Amount.of(5_000, USD), CaptureMethod.AUTOMATIC);
            payments.confirm(intent.paymentIntentId());

            List<String> types = outbox.findByAggregate(intent.paymentIntentId()).stream()
                    .map(OutboxEvent::eventType)
                    .toList();

            assertThat(types).containsExactly(
                    EventType.PAYMENT_INTENT_CREATED,
                    EventType.PAYMENT_INTENT_SUCCEEDED);
        }

        @Test
        void the_payload_carries_the_state_at_the_time_of_the_event() {
            PaymentIntent intent = newIntent(Amount.of(5_000, USD), CaptureMethod.AUTOMATIC);
            payments.confirm(intent.paymentIntentId());

            List<OutboxEvent> events = outbox.findByAggregate(intent.paymentIntentId());

            assertThat(events.get(0).payload()).contains("\"status\":\"requires_confirmation\"");
            assertThat(events.get(1).payload()).contains("\"status\":\"succeeded\"");
            assertThat(events.get(1).payload()).contains("\"amount_received\":5000");
        }
    }

    @Nested
    @DisplayName("the transactional guarantee")
    class Transactional {

        @Test
        void a_rolled_back_transaction_publishes_nothing() {
            long before = outbox.pendingCount();

            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                outbox.publish("test.event", "agg_rollback", Map.of("a", 1));
                // Something later in the same transaction goes wrong.
                throw new IllegalStateException("work failed after the event was queued");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(outbox.pendingCount())
                    .as("the event rolled back with the work")
                    .isEqualTo(before);
            assertThat(outboxRepository.findByAggregate("agg_rollback")).isEmpty();
        }

        @Test
        void a_failed_payment_leaves_no_events_behind() {
            // More than the customer has, so the ledger refuses it.
            PaymentIntent intent = newIntent(Amount.of(500_000, USD), CaptureMethod.AUTOMATIC);
            int afterCreate = outbox.findByAggregate(intent.paymentIntentId()).size();

            assertThatThrownBy(() -> payments.confirm(intent.paymentIntentId()))
                    .isInstanceOf(com.stripeclone.ledger.InsufficientFundsException.class);

            assertThat(outbox.findByAggregate(intent.paymentIntentId()))
                    .as("no succeeded event for a payment that never happened")
                    .hasSize(afterCreate);
        }
    }

    @Nested
    @DisplayName("the poller")
    class Polling {

        @Test
        void delivers_pending_events_and_marks_them_published() {
            String eventId = outbox.publish("test.event", "agg_2", Map.of("n", 1));

            int delivered = poller.drainOnceInNewTransaction();

            assertThat(delivered).isEqualTo(1);
            assertThat(handler.typesSeen()).containsExactly("test.event");
            assertThat(outbox.find(eventId).orElseThrow().status())
                    .isEqualTo(OutboxEvent.Status.PUBLISHED);
        }

        @Test
        void delivers_nothing_when_the_outbox_is_empty() {
            assertThat(poller.drainOnceInNewTransaction()).isZero();
            assertThat(handler.received).isEmpty();
        }

        @Test
        void does_not_deliver_the_same_event_twice_on_a_clean_run() {
            outbox.publish("test.event", "agg_3", Map.of("n", 1));

            poller.drainOnceInNewTransaction();
            poller.drainOnceInNewTransaction();

            assertThat(handler.received).hasSize(1);
        }

        @Test
        void retries_a_failing_handler_rather_than_dropping_the_event() {
            String eventId = outbox.publish("test.event", "agg_4", Map.of("n", 1));
            handler.failuresRemaining.set(1);

            int delivered = poller.drainOnceInNewTransaction();

            assertThat(delivered).isZero();
            OutboxEvent afterFailure = outbox.find(eventId).orElseThrow();
            assertThat(afterFailure.status()).isEqualTo(OutboxEvent.Status.PENDING);
            assertThat(afterFailure.attempts()).isEqualTo(1);
            assertThat(afterFailure.lastError()).contains("asked to fail");
            assertThat(afterFailure.nextAttemptAt()).isAfter(afterFailure.createdAt());
        }

        @Test
        void gives_up_after_too_many_attempts() {
            String eventId = outbox.publish("test.event", "agg_5", Map.of("n", 1));

            // Force the row to the edge of the attempt limit, then fail once more.
            jdbc.sql("UPDATE outbox SET attempts = 7 WHERE event_id = :id")
                    .param("id", eventId)
                    .update();
            handler.failuresRemaining.set(1);

            poller.drainOnceInNewTransaction();

            assertThat(outbox.find(eventId).orElseThrow().status())
                    .isEqualTo(OutboxEvent.Status.FAILED);
        }

        @Test
        void redelivers_an_event_whose_poller_died_before_marking_it() {
            String eventId = outbox.publish("test.event", "agg_6", Map.of("n", 1));
            poller.drainOnceInNewTransaction();
            assertThat(handler.received).hasSize(1);

            // Simulate a crash between the handler succeeding and the row being marked.
            OutboxEvent event = outbox.find(eventId).orElseThrow();
            outboxRepository.resetForRedelivery(event.id());

            poller.drainOnceInNewTransaction();

            assertThat(handler.received)
                    .as("at-least-once: the handler sees it again")
                    .hasSize(2);
            assertThat(handler.received.get(0).eventId())
                    .isEqualTo(handler.received.get(1).eventId());
        }

        @Test
        void backoff_doubles_and_then_stops_growing() {
            assertThat(poller.backoffFor(1)).isEqualTo(Duration.ofSeconds(2));
            assertThat(poller.backoffFor(2)).isEqualTo(Duration.ofSeconds(4));
            assertThat(poller.backoffFor(3)).isEqualTo(Duration.ofSeconds(8));
            assertThat(poller.backoffFor(20))
                    .as("capped so a stuck event isn't scheduled for next week")
                    .isEqualTo(Duration.ofHours(1));
        }

        @Test
        void delivers_events_oldest_first() {
            outbox.publish("test.first", "agg_7", Map.of("n", 1));
            outbox.publish("test.second", "agg_7", Map.of("n", 2));
            outbox.publish("test.third", "agg_7", Map.of("n", 3));

            poller.drainOnceInNewTransaction();

            assertThat(handler.typesSeen())
                    .containsExactly("test.first", "test.second", "test.third");
        }
    }
}
