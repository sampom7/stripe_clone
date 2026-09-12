package com.stripeclone.payment;

public class PaymentIntentNotFoundException extends RuntimeException {

    private final String paymentIntentId;

    public PaymentIntentNotFoundException(String paymentIntentId) {
        super("No such payment intent: " + paymentIntentId);
        this.paymentIntentId = paymentIntentId;
    }

    public String paymentIntentId() {
        return paymentIntentId;
    }
}
