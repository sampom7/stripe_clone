package com.stripeclone.payment;

import com.stripeclone.common.Ids;
import com.stripeclone.ledger.AccountType;
import com.stripeclone.ledger.LedgerService;
import com.stripeclone.ledger.LedgerTransaction;
import com.stripeclone.ledger.TransactionKind;
import com.stripeclone.money.Amount;
import com.stripeclone.money.Currency;
import com.stripeclone.outbox.EventType;
import com.stripeclone.outbox.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Payment intents, charges and refunds, all of it sitting on the ledger.
 *
 * <p>Every money movement here goes through {@link LedgerService#post}, which means the
 * zero-sum check, the account locking and the balance floor all apply without this class
 * reimplementing any of it.
 *
 * <p>The hold account is what makes authorize and capture work. Confirming moves the money
 * out of the customer's account and into a hold account belonging to that one intent.
 * Capturing moves it from the hold to the merchant. Whatever's left over goes back to the
 * customer in the same transaction. Because a hold is just an account, the amount held is
 * always its balance, and there's no separate counter to drift.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository repository;
    private final LedgerService ledger;
    private final OutboxService outbox;

    public PaymentService(
            PaymentRepository repository, LedgerService ledger, OutboxService outbox) {
        this.repository = repository;
        this.ledger = ledger;
        this.outbox = outbox;
    }

    @Transactional
    public Customer createCustomer(String email, String name, Currency currency) {
        String customerId = Ids.customer();
        String accountId = Ids.account();

        ledger.createAccount(accountId, AccountType.CUSTOMER, currency);
        Customer customer = new Customer(customerId, email, name, accountId, null);
        repository.insertCustomer(customer);

        outbox.publish(EventType.CUSTOMER_CREATED, customerId,
                Map.of("id", customerId, "email", email == null ? "" : email));

        log.debug("Created customer {} with account {}", customerId, accountId);
        return repository.findCustomer(customerId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<Customer> findCustomer(String customerId) {
        return repository.findCustomer(customerId);
    }

    /**
     * Creates an intent. No money moves yet; this only records what's being asked for.
     */
    @Transactional
    public PaymentIntent createIntent(CreateIntentRequest request) {
        if (!request.amount().isPositive()) {
            throw PaymentException.invalidRequest("Amount must be greater than zero");
        }

        String intentId = Ids.paymentIntent();
        Currency currency = request.amount().currency();

        PaymentIntent intent = new PaymentIntent(
                intentId,
                request.customerId(),
                request.customerAccount(),
                request.merchantAccount(),
                null,
                request.amount(),
                Amount.zero(currency),
                Amount.zero(currency),
                PaymentIntentStatus.REQUIRES_CONFIRMATION,
                request.captureMethod(),
                request.description(),
                null,
                null,
                null);

        repository.insertIntent(intent);
        emit(EventType.PAYMENT_INTENT_CREATED, intent);

        log.debug("Created payment intent {} for {}", intentId, request.amount());
        return repository.findIntent(intentId).orElseThrow();
    }

    /**
     * Confirms an intent, which moves the money out of the customer's account.
     *
     * <p>With automatic capture it goes straight to the merchant and the intent succeeds.
     * With manual capture it goes into a hold account and waits.
     */
    @Transactional
    public PaymentIntent confirm(String paymentIntentId) {
        PaymentIntent intent = lockIntent(paymentIntentId);

        if (intent.captureMethod() == CaptureMethod.MANUAL) {
            PaymentIntentStateMachine.assertTransition(
                    intent.status(), PaymentIntentStatus.REQUIRES_CAPTURE);

            String holdAccount = Ids.account();
            ledger.createAccount(holdAccount, AccountType.HOLD, intent.currency());
            repository.setHoldAccount(paymentIntentId, holdAccount);

            ledger.transfer(
                    Ids.transaction(),
                    TransactionKind.AUTHORIZATION,
                    intent.customerAccount(),
                    holdAccount,
                    intent.amount(),
                    "Authorization for " + paymentIntentId);

            repository.updateIntentAmounts(
                    paymentIntentId,
                    PaymentIntentStatus.REQUIRES_CAPTURE,
                    intent.amount(),
                    Amount.zero(intent.currency()));

            PaymentIntent authorized = repository.findIntent(paymentIntentId).orElseThrow();
            emit(EventType.PAYMENT_INTENT_AMOUNT_CAPTURABLE_UPDATED, authorized);

            log.debug("Authorized {} on intent {}", intent.amount(), paymentIntentId);
            return authorized;
        }

        PaymentIntentStateMachine.assertTransition(
                intent.status(), PaymentIntentStatus.SUCCEEDED);

        String txnId = Ids.transaction();
        ledger.transfer(
                txnId,
                TransactionKind.CAPTURE,
                intent.customerAccount(),
                intent.merchantAccount(),
                intent.amount(),
                "Payment " + paymentIntentId);

        recordCharge(paymentIntentId, intent.amount(), txnId);
        repository.updateIntentAmounts(
                paymentIntentId,
                PaymentIntentStatus.SUCCEEDED,
                Amount.zero(intent.currency()),
                intent.amount());

        PaymentIntent succeeded = repository.findIntent(paymentIntentId).orElseThrow();
        emit(EventType.PAYMENT_INTENT_SUCCEEDED, succeeded);

        log.debug("Captured {} on intent {}", intent.amount(), paymentIntentId);
        return succeeded;
    }

    /**
     * Captures an authorized intent, in full or in part.
     *
     * <p>A partial capture sends the requested amount to the merchant and returns the rest
     * to the customer, both in one balanced transaction. The hold account ends up empty
     * either way, which is the property the tests check: money can't be left stranded.
     *
     * @param amount how much to capture, or null for the whole hold
     */
    @Transactional
    public PaymentIntent capture(String paymentIntentId, Amount amount) {
        PaymentIntent intent = lockIntent(paymentIntentId);
        PaymentIntentStateMachine.assertTransition(
                intent.status(), PaymentIntentStatus.SUCCEEDED);

        Amount held = intent.amountCapturable();
        Amount toCapture = amount == null ? held : amount;

        if (!toCapture.isPositive()) {
            throw PaymentException.invalidRequest("Capture amount must be greater than zero");
        }
        if (toCapture.isGreaterThan(held)) {
            throw PaymentException.amountTooLarge(
                    "Cannot capture " + toCapture + "; only " + held + " is authorized");
        }

        Amount remainder = held.minus(toCapture);
        String txnId = Ids.transaction();

        LedgerTransaction.Builder builder = LedgerTransaction
                .builder(txnId, TransactionKind.CAPTURE)
                .debit(intent.holdAccount(), held)
                .credit(intent.merchantAccount(), toCapture)
                .description("Capture for " + paymentIntentId);

        if (remainder.isPositive()) {
            // Give back what wasn't taken, rather than leaving it stuck in the hold.
            builder.credit(intent.customerAccount(), remainder);
        }

        ledger.post(builder.build());
        recordCharge(paymentIntentId, toCapture, txnId);

        repository.updateIntentAmounts(
                paymentIntentId,
                PaymentIntentStatus.SUCCEEDED,
                Amount.zero(intent.currency()),
                toCapture);

        PaymentIntent captured = repository.findIntent(paymentIntentId).orElseThrow();
        emit(EventType.PAYMENT_INTENT_SUCCEEDED, captured);

        log.debug("Captured {} of {} on intent {}, returned {}",
                toCapture, held, paymentIntentId, remainder);
        return captured;
    }

    /**
     * Cancels an intent, releasing any hold back to the customer.
     */
    @Transactional
    public PaymentIntent cancel(String paymentIntentId, String reason) {
        PaymentIntent intent = lockIntent(paymentIntentId);
        PaymentIntentStateMachine.assertTransition(
                intent.status(), PaymentIntentStatus.CANCELED);

        if (intent.amountCapturable().isPositive() && intent.holdAccount() != null) {
            ledger.transfer(
                    Ids.transaction(),
                    TransactionKind.VOID,
                    intent.holdAccount(),
                    intent.customerAccount(),
                    intent.amountCapturable(),
                    "Void of " + paymentIntentId);
        }

        repository.setCancellation(paymentIntentId, reason);

        PaymentIntent canceled = repository.findIntent(paymentIntentId).orElseThrow();
        emit(EventType.PAYMENT_INTENT_CANCELED, canceled);

        log.debug("Canceled intent {} ({})", paymentIntentId, reason);
        return canceled;
    }

    /**
     * Refunds a charge, in full or in part, moving money back from merchant to customer.
     */
    @Transactional
    public Refund refund(String chargeId, Amount amount, String reason) {
        Charge charge = repository.findChargeForUpdate(chargeId)
                .orElseThrow(() -> new ChargeNotFoundException(chargeId));

        Amount refundable = charge.refundable();
        Amount toRefund = amount == null ? refundable : amount;

        if (!toRefund.isPositive()) {
            throw PaymentException.invalidRequest("Refund amount must be greater than zero");
        }
        if (toRefund.isGreaterThan(refundable)) {
            throw PaymentException.chargeAlreadyRefunded(
                    "Cannot refund " + toRefund + "; only " + refundable + " remains");
        }

        PaymentIntent intent = repository.findIntent(charge.paymentIntentId())
                .orElseThrow(() -> new PaymentIntentNotFoundException(charge.paymentIntentId()));

        String txnId = Ids.transaction();
        ledger.transfer(
                txnId,
                TransactionKind.REFUND,
                intent.merchantAccount(),
                intent.customerAccount(),
                toRefund,
                "Refund of " + chargeId);

        Amount newRefundedTotal = charge.amountRefunded().plus(toRefund);
        Charge.ChargeStatus newStatus = newRefundedTotal.equals(charge.amount())
                ? Charge.ChargeStatus.REFUNDED
                : Charge.ChargeStatus.SUCCEEDED;
        repository.updateChargeRefunded(chargeId, newRefundedTotal, newStatus);

        Refund refund = new Refund(
                Ids.refund(), chargeId, toRefund, reason,
                Refund.RefundStatus.SUCCEEDED, txnId, null);
        repository.insertRefund(refund);

        outbox.publish(EventType.CHARGE_REFUNDED, chargeId, Map.of(
                "id", chargeId,
                "amount_refunded", newRefundedTotal.minorUnits(),
                "currency", toRefund.currency().name().toLowerCase(),
                "refunded", newStatus == Charge.ChargeStatus.REFUNDED));

        log.debug("Refunded {} of charge {}", toRefund, chargeId);
        return repository.findRefund(refund.refundId()).orElseThrow();
    }


    // -------------------------------------------------------- payment methods

    /**
     * Registers a card.
     *
     * <p>The number is used to work out the brand, the last four and the fingerprint, and
     * is then dropped. Nothing that could be replayed is written anywhere.
     */
    @Transactional
    public PaymentMethod createCard(String cardNumber, int expMonth, int expYear) {
        if (!TestCards.passesLuhn(cardNumber)) {
            throw PaymentException.invalidRequest("Your card number is invalid.");
        }

        PaymentMethod method = new PaymentMethod(
                Ids.paymentMethod(),
                null,
                "card",
                TestCards.brandOf(cardNumber),
                TestCards.last4(cardNumber),
                expMonth,
                expYear,
                TestCards.fingerprint(cardNumber),
                null);

        repository.insertPaymentMethod(method);
        log.debug("Created payment method {} ({} ending {})",
                method.paymentMethodId(), method.brand(), method.last4());
        return repository.findPaymentMethod(method.paymentMethodId()).orElseThrow();
    }

    @Transactional
    public PaymentMethod attach(String paymentMethodId, String customerId) {
        repository.findPaymentMethod(paymentMethodId)
                .orElseThrow(() -> new PaymentMethodNotFoundException(paymentMethodId));
        repository.findCustomer(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));

        repository.attachPaymentMethod(paymentMethodId, customerId);
        return repository.findPaymentMethod(paymentMethodId).orElseThrow();
    }

    @Transactional
    public PaymentMethod detach(String paymentMethodId) {
        repository.findPaymentMethod(paymentMethodId)
                .orElseThrow(() -> new PaymentMethodNotFoundException(paymentMethodId));
        repository.detachPaymentMethod(paymentMethodId);
        return repository.findPaymentMethod(paymentMethodId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public PaymentMethod getPaymentMethod(String paymentMethodId) {
        return repository.findPaymentMethod(paymentMethodId)
                .orElseThrow(() -> new PaymentMethodNotFoundException(paymentMethodId));
    }

    @Transactional(readOnly = true)
    public List<PaymentMethod> paymentMethodsFor(String customerId) {
        return repository.findPaymentMethodsForCustomer(customerId);
    }

    /**
     * Confirms an intent using a specific card, running it past the simulated network first.
     *
     * <p>Two separate things can go wrong and they're worth telling apart. The network can
     * refuse the card, which is {@link CardDeclinedException}. Or the network can approve it
     * and the ledger can still refuse because the money isn't there, which is
     * {@link com.stripeclone.ledger.InsufficientFundsException}. A real processor sees the
     * same split.
     */
    @Transactional
    public PaymentIntent confirmWithCard(
            String paymentIntentId, String paymentMethodId, String cardNumber) {

        PaymentMethod method = getPaymentMethod(paymentMethodId);
        repository.setIntentPaymentMethod(paymentIntentId, method.paymentMethodId());

        TestCards.Outcome outcome = TestCards.outcomeFor(cardNumber);
        if (!outcome.isApproved()) {
            log.debug("Card {} declined on intent {}: {}",
                    method.last4(), paymentIntentId, outcome.declineCode());
            throw new CardDeclinedException(outcome);
        }

        return confirm(paymentIntentId);
    }

    @Transactional(readOnly = true)
    public PaymentIntent getIntent(String paymentIntentId) {
        return repository.findIntent(paymentIntentId)
                .orElseThrow(() -> new PaymentIntentNotFoundException(paymentIntentId));
    }

    @Transactional(readOnly = true)
    public List<PaymentIntent> listIntents(int limit, String startingAfter) {
        return repository.listIntents(limit, startingAfter);
    }

    @Transactional(readOnly = true)
    public Charge getCharge(String chargeId) {
        return repository.findCharge(chargeId)
                .orElseThrow(() -> new ChargeNotFoundException(chargeId));
    }

    @Transactional(readOnly = true)
    public List<Charge> chargesFor(String paymentIntentId) {
        return repository.findChargesForIntent(paymentIntentId);
    }

    @Transactional(readOnly = true)
    public List<Refund> refundsFor(String chargeId) {
        return repository.findRefundsForCharge(chargeId);
    }

    private PaymentIntent lockIntent(String paymentIntentId) {
        return repository.findIntentForUpdate(paymentIntentId)
                .orElseThrow(() -> new PaymentIntentNotFoundException(paymentIntentId));
    }

    private void recordCharge(String paymentIntentId, Amount amount, String ledgerTxnId) {
        Charge charge = new Charge(
                Ids.charge(),
                paymentIntentId,
                amount,
                Amount.zero(amount.currency()),
                Charge.ChargeStatus.SUCCEEDED,
                ledgerTxnId,
                null);
        repository.insertCharge(charge);

        outbox.publish(EventType.CHARGE_SUCCEEDED, charge.chargeId(), Map.of(
                "id", charge.chargeId(),
                "payment_intent", paymentIntentId,
                "amount", amount.minorUnits(),
                "currency", amount.currency().name().toLowerCase()));
    }

    /** Queues an event describing an intent's current state. */
    private void emit(String eventType, PaymentIntent intent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", intent.paymentIntentId());
        payload.put("object", "payment_intent");
        payload.put("amount", intent.amount().minorUnits());
        payload.put("amount_capturable", intent.amountCapturable().minorUnits());
        payload.put("amount_received", intent.amountReceived().minorUnits());
        payload.put("currency", intent.currency().name().toLowerCase());
        payload.put("status", intent.status().wireValue());
        payload.put("capture_method", intent.captureMethod().wireValue());
        if (intent.customerId() != null) {
            payload.put("customer", intent.customerId());
        }
        outbox.publish(eventType, intent.paymentIntentId(), payload);
    }

    /** What's needed to open an intent. */
    public record CreateIntentRequest(
            String customerId,
            String customerAccount,
            String merchantAccount,
            Amount amount,
            CaptureMethod captureMethod,
            String description
    ) {}
}
