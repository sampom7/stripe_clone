package com.stripeclone.payment;

import com.stripeclone.PostgresTestBase;
import com.stripeclone.common.Ids;
import com.stripeclone.ledger.AccountType;
import com.stripeclone.ledger.LedgerService;
import com.stripeclone.ledger.TransactionKind;
import com.stripeclone.money.Amount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static com.stripeclone.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentServiceTest extends PostgresTestBase {

    @Autowired
    PaymentService payments;

    @Autowired
    LedgerService ledger;

    private static final String EXTERNAL = "acct_external_usd";
    private static final Amount HUNDRED = Amount.of(10_000, USD);

    private Customer customer;
    private String merchantAccount;

    @BeforeEach
    void seed() {
        ledger.createAccount(EXTERNAL, AccountType.EXTERNAL, USD);

        customer = payments.createCustomer("buyer@example.com", "A Buyer", USD);
        ledger.transfer(Ids.transaction(), TransactionKind.FUNDING,
                EXTERNAL, customer.accountId(), Amount.of(100_000, USD), "funding");

        merchantAccount = Ids.account();
        ledger.createAccount(merchantAccount, AccountType.MERCHANT, USD);
    }

    private PaymentIntent newIntent(Amount amount, CaptureMethod method) {
        return payments.createIntent(new PaymentService.CreateIntentRequest(
                customer.customerId(), customer.accountId(), merchantAccount,
                amount, method, "test payment"));
    }

    @Nested
    @DisplayName("automatic capture")
    class Automatic {

        @Test
        void confirming_moves_money_straight_to_the_merchant() {
            PaymentIntent intent = newIntent(HUNDRED, CaptureMethod.AUTOMATIC);
            assertThat(intent.status()).isEqualTo(PaymentIntentStatus.REQUIRES_CONFIRMATION);

            PaymentIntent confirmed = payments.confirm(intent.paymentIntentId());

            assertThat(confirmed.status()).isEqualTo(PaymentIntentStatus.SUCCEEDED);
            assertThat(confirmed.amountReceived()).isEqualTo(HUNDRED);
            assertThat(ledger.balanceOf(merchantAccount)).isEqualTo(HUNDRED);
            assertThat(ledger.balanceOf(customer.accountId())).isEqualTo(Amount.of(90_000, USD));
        }

        @Test
        void the_customer_is_debited_exactly_once() {
            // The v1 implementation of this project completed a hold and then also posted
            // a debit entry, so every capture took the money twice. This is here to make
            // sure that can never come back.
            PaymentIntent intent = newIntent(HUNDRED, CaptureMethod.AUTOMATIC);
            Amount before = ledger.balanceOf(customer.accountId());

            payments.confirm(intent.paymentIntentId());

            Amount after = ledger.balanceOf(customer.accountId());
            assertThat(before.minus(after))
                    .as("customer debited once, not twice")
                    .isEqualTo(HUNDRED);
            assertThat(ledger.isBalanced()).isTrue();
        }

        @Test
        void records_one_charge() {
            PaymentIntent intent = newIntent(HUNDRED, CaptureMethod.AUTOMATIC);
            payments.confirm(intent.paymentIntentId());

            List<Charge> charges = payments.chargesFor(intent.paymentIntentId());
            assertThat(charges).hasSize(1);
            assertThat(charges.get(0).amount()).isEqualTo(HUNDRED);
            assertThat(charges.get(0).status()).isEqualTo(Charge.ChargeStatus.SUCCEEDED);
        }

        @Test
        void is_refused_when_the_customer_cannot_afford_it() {
            PaymentIntent intent = newIntent(Amount.of(500_000, USD), CaptureMethod.AUTOMATIC);

            assertThatThrownBy(() -> payments.confirm(intent.paymentIntentId()))
                    .isInstanceOf(com.stripeclone.ledger.InsufficientFundsException.class);

            assertThat(ledger.isBalanced()).isTrue();
        }
    }

    @Nested
    @DisplayName("manual capture")
    class Manual {

        @Test
        void confirming_parks_the_money_in_a_hold_account() {
            PaymentIntent intent = newIntent(HUNDRED, CaptureMethod.MANUAL);
            PaymentIntent authorized = payments.confirm(intent.paymentIntentId());

            assertThat(authorized.status()).isEqualTo(PaymentIntentStatus.REQUIRES_CAPTURE);
            assertThat(authorized.amountCapturable()).isEqualTo(HUNDRED);
            assertThat(authorized.holdAccount()).isNotNull();

            // Money has left the customer but hasn't reached the merchant yet.
            assertThat(ledger.balanceOf(customer.accountId())).isEqualTo(Amount.of(90_000, USD));
            assertThat(ledger.balanceOf(authorized.holdAccount())).isEqualTo(HUNDRED);
            assertThat(ledger.balanceOf(merchantAccount)).isEqualTo(Amount.zero(USD));
        }

        @Test
        void capturing_in_full_empties_the_hold() {
            PaymentIntent intent = newIntent(HUNDRED, CaptureMethod.MANUAL);
            PaymentIntent authorized = payments.confirm(intent.paymentIntentId());

            PaymentIntent captured = payments.capture(intent.paymentIntentId(), null);

            assertThat(captured.status()).isEqualTo(PaymentIntentStatus.SUCCEEDED);
            assertThat(captured.amountReceived()).isEqualTo(HUNDRED);
            assertThat(ledger.balanceOf(merchantAccount)).isEqualTo(HUNDRED);
            assertThat(ledger.balanceOf(authorized.holdAccount())).isEqualTo(Amount.zero(USD));
        }

        @Test
        void a_partial_capture_returns_the_rest_and_leaves_the_hold_empty() {
            PaymentIntent intent = newIntent(HUNDRED, CaptureMethod.MANUAL);
            PaymentIntent authorized = payments.confirm(intent.paymentIntentId());

            payments.capture(intent.paymentIntentId(), Amount.of(6_000, USD));

            assertThat(ledger.balanceOf(merchantAccount)).isEqualTo(Amount.of(6_000, USD));
            assertThat(ledger.balanceOf(authorized.holdAccount()))
                    .as("nothing stranded in the hold")
                    .isEqualTo(Amount.zero(USD));
            // 100_000 start, 10_000 authorized, 4_000 handed back.
            assertThat(ledger.balanceOf(customer.accountId())).isEqualTo(Amount.of(94_000, USD));
            assertThat(ledger.isBalanced()).isTrue();
        }

        @Test
        void capturing_more_than_was_authorized_is_refused() {
            PaymentIntent intent = newIntent(HUNDRED, CaptureMethod.MANUAL);
            payments.confirm(intent.paymentIntentId());

            assertThatThrownBy(() ->
                    payments.capture(intent.paymentIntentId(), Amount.of(20_000, USD)))
                    .isInstanceOf(PaymentException.class)
                    .hasMessageContaining("only");
        }

        @Test
        void capturing_twice_is_refused() {
            PaymentIntent intent = newIntent(HUNDRED, CaptureMethod.MANUAL);
            payments.confirm(intent.paymentIntentId());
            payments.capture(intent.paymentIntentId(), null);

            assertThatThrownBy(() -> payments.capture(intent.paymentIntentId(), null))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        void canceling_gives_the_hold_back() {
            PaymentIntent intent = newIntent(HUNDRED, CaptureMethod.MANUAL);
            PaymentIntent authorized = payments.confirm(intent.paymentIntentId());

            PaymentIntent canceled = payments.cancel(
                    intent.paymentIntentId(), "requested_by_customer");

            assertThat(canceled.status()).isEqualTo(PaymentIntentStatus.CANCELED);
            assertThat(ledger.balanceOf(customer.accountId())).isEqualTo(Amount.of(100_000, USD));
            assertThat(ledger.balanceOf(authorized.holdAccount())).isEqualTo(Amount.zero(USD));
            assertThat(ledger.balanceOf(merchantAccount)).isEqualTo(Amount.zero(USD));
        }
    }

    @Nested
    @DisplayName("refunds")
    class Refunds {

        private Charge succeededCharge() {
            PaymentIntent intent = newIntent(HUNDRED, CaptureMethod.AUTOMATIC);
            payments.confirm(intent.paymentIntentId());
            return payments.chargesFor(intent.paymentIntentId()).get(0);
        }

        @Test
        void a_full_refund_puts_the_money_back() {
            Charge charge = succeededCharge();

            Refund refund = payments.refund(charge.chargeId(), null, "requested_by_customer");

            assertThat(refund.amount()).isEqualTo(HUNDRED);
            assertThat(refund.status()).isEqualTo(Refund.RefundStatus.SUCCEEDED);
            assertThat(ledger.balanceOf(customer.accountId())).isEqualTo(Amount.of(100_000, USD));
            assertThat(ledger.balanceOf(merchantAccount)).isEqualTo(Amount.zero(USD));
            assertThat(payments.getCharge(charge.chargeId()).status())
                    .isEqualTo(Charge.ChargeStatus.REFUNDED);
        }

        @Test
        void partial_refunds_add_up_and_stop_at_the_charge_amount() {
            Charge charge = succeededCharge();

            payments.refund(charge.chargeId(), Amount.of(3_000, USD), "duplicate");
            payments.refund(charge.chargeId(), Amount.of(7_000, USD), "duplicate");

            Charge after = payments.getCharge(charge.chargeId());
            assertThat(after.amountRefunded()).isEqualTo(HUNDRED);
            assertThat(after.status()).isEqualTo(Charge.ChargeStatus.REFUNDED);
            assertThat(payments.refundsFor(charge.chargeId())).hasSize(2);

            assertThatThrownBy(() ->
                    payments.refund(charge.chargeId(), Amount.of(1, USD), "duplicate"))
                    .isInstanceOf(PaymentException.class);
        }

        @Test
        void refunding_more_than_the_charge_is_refused() {
            Charge charge = succeededCharge();

            assertThatThrownBy(() ->
                    payments.refund(charge.chargeId(), Amount.of(20_000, USD), "duplicate"))
                    .isInstanceOf(PaymentException.class)
                    .hasMessageContaining("remains");

            assertThat(ledger.isBalanced()).isTrue();
        }
    }

    @Nested
    @DisplayName("state machine")
    class States {

        @Test
        void confirming_a_canceled_intent_is_refused() {
            PaymentIntent intent = newIntent(HUNDRED, CaptureMethod.MANUAL);
            payments.cancel(intent.paymentIntentId(), "abandoned");

            assertThatThrownBy(() -> payments.confirm(intent.paymentIntentId()))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        void canceling_a_succeeded_intent_is_refused() {
            PaymentIntent intent = newIntent(HUNDRED, CaptureMethod.AUTOMATIC);
            payments.confirm(intent.paymentIntentId());

            assertThatThrownBy(() -> payments.cancel(intent.paymentIntentId(), "too late"))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        void capturing_something_never_authorized_is_refused() {
            PaymentIntent intent = newIntent(HUNDRED, CaptureMethod.MANUAL);

            assertThatThrownBy(() -> payments.capture(intent.paymentIntentId(), null))
                    .isInstanceOf(PaymentException.class);
        }

        @Test
        void terminal_states_allow_nothing() {
            assertThat(PaymentIntentStateMachine.allowedFrom(PaymentIntentStatus.SUCCEEDED))
                    .isEmpty();
            assertThat(PaymentIntentStateMachine.allowedFrom(PaymentIntentStatus.CANCELED))
                    .isEmpty();
        }

        @Test
        void the_ledger_still_balances_after_every_operation() {
            PaymentIntent a = newIntent(Amount.of(5_000, USD), CaptureMethod.AUTOMATIC);
            payments.confirm(a.paymentIntentId());

            PaymentIntent b = newIntent(Amount.of(3_000, USD), CaptureMethod.MANUAL);
            payments.confirm(b.paymentIntentId());
            payments.capture(b.paymentIntentId(), Amount.of(1_500, USD));

            PaymentIntent c = newIntent(Amount.of(2_000, USD), CaptureMethod.MANUAL);
            payments.confirm(c.paymentIntentId());
            payments.cancel(c.paymentIntentId(), "changed_mind");

            Charge charge = payments.chargesFor(a.paymentIntentId()).get(0);
            payments.refund(charge.chargeId(), Amount.of(2_000, USD), "duplicate");

            assertThat(ledger.isBalanced()).isTrue();
            assertThat(ledger.findDriftedAccounts()).isEmpty();
        }
    }
}
