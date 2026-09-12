package com.stripeclone.payment;

/** Thrown when an operation doesn't make sense for the intent's current status. */
public class InvalidStateTransitionException extends RuntimeException {

    private final PaymentIntentStatus from;
    private final PaymentIntentStatus to;

    public InvalidStateTransitionException(PaymentIntentStatus from, PaymentIntentStatus to) {
        super("Cannot move a payment intent from " + from.wireValue() + " to " + to.wireValue());
        this.from = from;
        this.to = to;
    }

    public PaymentIntentStatus from() {
        return from;
    }

    public PaymentIntentStatus to() {
        return to;
    }
}
