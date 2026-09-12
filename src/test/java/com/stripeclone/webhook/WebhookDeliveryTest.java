package com.stripeclone.webhook;

import com.stripeclone.PostgresTestBase;
import com.stripeclone.common.Ids;
import com.stripeclone.ledger.AccountType;
import com.stripeclone.ledger.LedgerService;
import com.stripeclone.ledger.TransactionKind;
import com.stripeclone.money.Amount;
import com.stripeclone.outbox.EventType;
import com.stripeclone.outbox.OutboxPoller;
import com.stripeclone.outbox.OutboxService;
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
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.stripeclone.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;

@Import(WebhookDeliveryTest.RecordingSenderConfig.class)
class WebhookDeliveryTest extends PostgresTestBase {

    /**
     * Stands in for a real receiver. Records what arrived and returns whatever status the
     * test asks for, which is how the retry paths get exercised without running a server
     * that fails on command.
     */
    static class RecordingSender implements WebhookSender {

        record Call(String url, String payload, String signature, String eventId) {}

        final List<Call> calls = new ArrayList<>();
        final AtomicInteger failuresRemaining = new AtomicInteger(0);
        volatile int failureStatus = 500;

        @Override
        public synchronized Result send(
                String url, String payload, String signature, String eventId) {

            calls.add(new Call(url, payload, signature, eventId));

            if (failuresRemaining.get() > 0) {
                failuresRemaining.decrementAndGet();
                return Result.failed(failureStatus, "Endpoint returned " + failureStatus);
            }
            return Result.ok(200);
        }

        synchronized void reset() {
            calls.clear();
            failuresRemaining.set(0);
            failureStatus = 500;
        }
    }

    @TestConfiguration
    static class RecordingSenderConfig {
        @Bean
        @Primary
        RecordingSender recordingSender() {
            return new RecordingSender();
        }
    }

    @Autowired
    WebhookService webhooks;

    @Autowired
    WebhookRepository webhookRepository;

    @Autowired
    WebhookDeliveryPoller deliveryPoller;

    @Autowired
    OutboxService outbox;

    @Autowired
    OutboxPoller outboxPoller;

    @Autowired
    PaymentService payments;

    @Autowired
    LedgerService ledger;

    @Autowired
    RecordingSender sender;

    private static final String EXTERNAL = "acct_external_usd";
    private Customer customer;
    private String merchantAccount;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE TABLE webhook_deliveries, webhook_endpoints RESTART IDENTITY CASCADE")
                .update();
        sender.reset();

        ledger.createAccount(EXTERNAL, AccountType.EXTERNAL, USD);
        customer = payments.createCustomer("buyer@example.com", "A Buyer", USD);
        ledger.transfer(Ids.transaction(), TransactionKind.FUNDING,
                EXTERNAL, customer.accountId(), Amount.of(100_000, USD), "funding");

        merchantAccount = Ids.account();
        ledger.createAccount(merchantAccount, AccountType.MERCHANT, USD);

        jdbc.sql("TRUNCATE TABLE outbox RESTART IDENTITY").update();
    }

    private WebhookEndpoint endpointFor(String... events) {
        return webhooks.register("https://example.test/hook", Set.of(events), "test endpoint");
    }

    private PaymentIntent paidIntent(long amount) {
        PaymentIntent intent = payments.createIntent(new PaymentService.CreateIntentRequest(
                customer.customerId(), customer.accountId(), merchantAccount,
                Amount.of(amount, USD), CaptureMethod.AUTOMATIC, "test"));
        return payments.confirm(intent.paymentIntentId());
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        void an_endpoint_gets_its_own_secret() {
            WebhookEndpoint first = endpointFor("*");
            WebhookEndpoint second = endpointFor("*");

            assertThat(first.secret()).startsWith("whsec_").isNotEqualTo(second.secret());
        }

        @Test
        void an_endpoint_only_wants_the_events_it_subscribed_to() {
            WebhookEndpoint endpoint = endpointFor(EventType.CHARGE_SUCCEEDED);

            assertThat(endpoint.wants(EventType.CHARGE_SUCCEEDED)).isTrue();
            assertThat(endpoint.wants(EventType.PAYMENT_INTENT_CANCELED)).isFalse();
        }

        @Test
        void a_star_subscription_wants_everything() {
            WebhookEndpoint endpoint = endpointFor("*");

            assertThat(endpoint.wants(EventType.CHARGE_SUCCEEDED)).isTrue();
            assertThat(endpoint.wants("anything.at.all")).isTrue();
        }

        @Test
        void a_disabled_endpoint_wants_nothing() {
            WebhookEndpoint endpoint = endpointFor("*");
            WebhookEndpoint disabled =
                    webhooks.setStatus(endpoint.endpointId(), WebhookEndpoint.Status.DISABLED);

            assertThat(disabled.wants(EventType.CHARGE_SUCCEEDED)).isFalse();
        }
    }

    @Nested
    @DisplayName("fan-out")
    class FanOut {

        @Test
        void one_event_reaches_every_subscribed_endpoint() {
            endpointFor("*");
            endpointFor("*");
            endpointFor(EventType.PAYMENT_INTENT_CANCELED);   // not interested

            String eventId = outbox.publish(EventType.CHARGE_SUCCEEDED, "ch_1",
                    Map.of("id", "ch_1"));
            int queued = webhooks.fanOut(eventId, EventType.CHARGE_SUCCEEDED, "{}");

            assertThat(queued).isEqualTo(2);
            assertThat(webhooks.deliveriesForEvent(eventId)).hasSize(2);
        }

        @Test
        void queueing_the_same_event_twice_does_not_double_up() {
            // The outbox is at-least-once, so the fan-out handler will see repeats.
            endpointFor("*");
            String eventId = outbox.publish(EventType.CHARGE_SUCCEEDED, "ch_2",
                    Map.of("id", "ch_2"));

            assertThat(webhooks.fanOut(eventId, EventType.CHARGE_SUCCEEDED, "{}")).isEqualTo(1);
            assertThat(webhooks.fanOut(eventId, EventType.CHARGE_SUCCEEDED, "{}")).isZero();
            assertThat(webhooks.deliveriesForEvent(eventId)).hasSize(1);
        }

        @Test
        void a_payment_flows_all_the_way_to_a_queued_delivery() {
            endpointFor("*");

            PaymentIntent intent = paidIntent(2000);
            outboxPoller.drainOnceInNewTransaction();

            List<WebhookDelivery> queued = outbox.findByAggregate(intent.paymentIntentId())
                    .stream()
                    .flatMap(e -> webhooks.deliveriesForEvent(e.eventId()).stream())
                    .toList();

            assertThat(queued).isNotEmpty();
            assertThat(queued).allMatch(d -> d.status() == WebhookDelivery.Status.PENDING);
        }
    }

    @Nested
    @DisplayName("delivery")
    class Delivery {

        @Test
        void a_queued_delivery_is_sent_and_marked_delivered() {
            WebhookEndpoint endpoint = endpointFor("*");
            String eventId = outbox.publish(EventType.CHARGE_SUCCEEDED, "ch_3",
                    Map.of("id", "ch_3"));
            webhooks.fanOut(eventId, EventType.CHARGE_SUCCEEDED, "{\"id\":\"ch_3\"}");

            int delivered = deliveryPoller.deliverOnceInNewTransaction();

            assertThat(delivered).isEqualTo(1);
            assertThat(sender.calls).hasSize(1);
            assertThat(sender.calls.get(0).url()).isEqualTo(endpoint.url());
            assertThat(webhooks.deliveriesForEvent(eventId).get(0).status())
                    .isEqualTo(WebhookDelivery.Status.DELIVERED);
        }

        @Test
        void what_arrives_is_signed_with_the_endpoints_own_secret() {
            WebhookEndpoint endpoint = endpointFor("*");
            String payload = "{\"id\":\"ch_4\",\"type\":\"charge.succeeded\"}";
            String eventId = outbox.publish(EventType.CHARGE_SUCCEEDED, "ch_4",
                    Map.of("id", "ch_4"));
            webhooks.fanOut(eventId, EventType.CHARGE_SUCCEEDED, payload);

            deliveryPoller.deliverOnceInNewTransaction();

            RecordingSender.Call call = sender.calls.get(0);
            assertThat(WebhookSignature.verify(call.payload(), call.signature(),
                    endpoint.secret()))
                    .as("receiver can verify with the secret it was given")
                    .isTrue();
            assertThat(WebhookSignature.verify(call.payload(), call.signature(),
                    "whsec_someone_elses_secret"))
                    .isFalse();
        }

        @Test
        void a_failing_endpoint_is_retried_rather_than_dropped() {
            endpointFor("*");
            String eventId = outbox.publish(EventType.CHARGE_SUCCEEDED, "ch_5",
                    Map.of("id", "ch_5"));
            webhooks.fanOut(eventId, EventType.CHARGE_SUCCEEDED, "{}");

            sender.failuresRemaining.set(1);
            sender.failureStatus = 503;

            assertThat(deliveryPoller.deliverOnceInNewTransaction()).isZero();

            WebhookDelivery afterFailure = webhooks.deliveriesForEvent(eventId).get(0);
            assertThat(afterFailure.status()).isEqualTo(WebhookDelivery.Status.PENDING);
            assertThat(afterFailure.attempts()).isEqualTo(1);
            assertThat(afterFailure.responseStatus()).isEqualTo(503);
            assertThat(afterFailure.nextAttemptAt()).isAfter(afterFailure.createdAt());
        }

        @Test
        void a_retry_that_succeeds_marks_it_delivered() {
            endpointFor("*");
            String eventId = outbox.publish(EventType.CHARGE_SUCCEEDED, "ch_6",
                    Map.of("id", "ch_6"));
            webhooks.fanOut(eventId, EventType.CHARGE_SUCCEEDED, "{}");

            sender.failuresRemaining.set(1);
            deliveryPoller.deliverOnceInNewTransaction();

            // Pretend the backoff has elapsed.
            jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = now()").update();
            deliveryPoller.deliverOnceInNewTransaction();

            assertThat(sender.calls).hasSize(2);
            assertThat(webhooks.deliveriesForEvent(eventId).get(0).status())
                    .isEqualTo(WebhookDelivery.Status.DELIVERED);
        }

        @Test
        void it_gives_up_after_the_attempt_limit() {
            endpointFor("*");
            String eventId = outbox.publish(EventType.CHARGE_SUCCEEDED, "ch_7",
                    Map.of("id", "ch_7"));
            webhooks.fanOut(eventId, EventType.CHARGE_SUCCEEDED, "{}");

            jdbc.sql("UPDATE webhook_deliveries SET attempts = 7").update();
            sender.failuresRemaining.set(1);

            deliveryPoller.deliverOnceInNewTransaction();

            assertThat(webhooks.deliveriesForEvent(eventId).get(0).status())
                    .isEqualTo(WebhookDelivery.Status.FAILED);
        }

        @Test
        void a_delivery_is_not_retried_before_its_backoff_has_passed() {
            endpointFor("*");
            String eventId = outbox.publish(EventType.CHARGE_SUCCEEDED, "ch_8",
                    Map.of("id", "ch_8"));
            webhooks.fanOut(eventId, EventType.CHARGE_SUCCEEDED, "{}");

            sender.failuresRemaining.set(1);
            deliveryPoller.deliverOnceInNewTransaction();
            int callsAfterFirstAttempt = sender.calls.size();

            // Next attempt is a minute out, so this pass should find nothing due.
            assertThat(deliveryPoller.deliverOnceInNewTransaction()).isZero();
            assertThat(sender.calls).hasSize(callsAfterFirstAttempt);
        }

        @Test
        void a_failed_delivery_can_be_replayed() {
            endpointFor("*");
            String eventId = outbox.publish(EventType.CHARGE_SUCCEEDED, "ch_9",
                    Map.of("id", "ch_9"));
            webhooks.fanOut(eventId, EventType.CHARGE_SUCCEEDED, "{}");

            jdbc.sql("UPDATE webhook_deliveries SET attempts = 7").update();
            sender.failuresRemaining.set(1);
            deliveryPoller.deliverOnceInNewTransaction();

            WebhookDelivery failed = webhooks.deliveriesForEvent(eventId).get(0);
            assertThat(failed.status()).isEqualTo(WebhookDelivery.Status.FAILED);

            webhooks.replay(failed.deliveryId());
            deliveryPoller.deliverOnceInNewTransaction();

            assertThat(webhooks.findDelivery(failed.deliveryId()).orElseThrow().status())
                    .isEqualTo(WebhookDelivery.Status.DELIVERED);
        }

        @Test
        void a_delivery_whose_endpoint_was_deleted_fails_cleanly() {
            WebhookEndpoint endpoint = endpointFor("*");
            String eventId = outbox.publish(EventType.CHARGE_SUCCEEDED, "ch_10",
                    Map.of("id", "ch_10"));
            webhooks.fanOut(eventId, EventType.CHARGE_SUCCEEDED, "{}");

            // Deleting takes its deliveries with it, so nothing is left to send.
            webhooks.delete(endpoint.endpointId());

            assertThat(deliveryPoller.deliverOnceInNewTransaction()).isZero();
            assertThat(sender.calls).isEmpty();
        }

        @Test
        void backoff_doubles_from_a_minute_and_caps_at_a_day() {
            assertThat(deliveryPoller.backoffFor(1)).isEqualTo(Duration.ofMinutes(1));
            assertThat(deliveryPoller.backoffFor(2)).isEqualTo(Duration.ofMinutes(2));
            assertThat(deliveryPoller.backoffFor(3)).isEqualTo(Duration.ofMinutes(4));
            assertThat(deliveryPoller.backoffFor(30)).isEqualTo(Duration.ofDays(1));
        }
    }
}
