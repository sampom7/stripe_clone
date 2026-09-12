package com.stripeclone.payment;

/** Stripe's payment intent statuses, spelled the way they appear on the wire. */
public enum PaymentIntentStatus {

    REQUIRES_PAYMENT_METHOD("requires_payment_method"),
    REQUIRES_CONFIRMATION("requires_confirmation"),
    REQUIRES_CAPTURE("requires_capture"),
    PROCESSING("processing"),
    SUCCEEDED("succeeded"),
    CANCELED("canceled");

    private final String wireValue;

    PaymentIntentStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static PaymentIntentStatus fromWire(String value) {
        for (PaymentIntentStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown payment intent status: " + value);
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == CANCELED;
    }
}
