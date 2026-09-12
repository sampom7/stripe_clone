package com.stripeclone.payment;

/**
 * A payment operation that failed for a business reason rather than a bug.
 *
 * <p>Carries a Stripe-style error code so the HTTP layer can map it without a second
 * lookup table.
 */
public class PaymentException extends RuntimeException {

    private final String code;

    public PaymentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static PaymentException amountTooLarge(String message) {
        return new PaymentException("amount_too_large", message);
    }

    public static PaymentException invalidRequest(String message) {
        return new PaymentException("invalid_request_error", message);
    }

    public static PaymentException alreadyCaptured(String message) {
        return new PaymentException("payment_intent_unexpected_state", message);
    }

    public static PaymentException chargeAlreadyRefunded(String message) {
        return new PaymentException("charge_already_refunded", message);
    }
}
