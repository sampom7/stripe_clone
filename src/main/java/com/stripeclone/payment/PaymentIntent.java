package com.stripeclone.payment;

import com.stripeclone.money.Amount;
import com.stripeclone.money.Currency;

import java.time.Instant;

/**
 * A payment intent.
 *
 * <p>The amounts mirror Stripe's: {@code amount} is what was asked for, {@code
 * amountCapturable} is what's sitting in the hold account waiting, and {@code
 * amountReceived} is what actually reached the merchant. All three have ledger entries
 * behind them; this record is just a convenient view.
 */
public record PaymentIntent(
        String paymentIntentId,
        String customerId,
        String customerAccount,
        String merchantAccount,
        String holdAccount,
        Amount amount,
        Amount amountCapturable,
        Amount amountReceived,
        PaymentIntentStatus status,
        CaptureMethod captureMethod,
        String description,
        String cancellationReason,
        Instant createdAt,
        Instant updatedAt
) {

    public Currency currency() {
        return amount.currency();
    }

    /** What's left of the hold after any partial captures. */
    public Amount remainingCapturable() {
        return amountCapturable;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }
}
