package com.stripeclone.payment;

public class PaymentMethodNotFoundException extends RuntimeException {

    private final String paymentMethodId;

    public PaymentMethodNotFoundException(String paymentMethodId) {
        super("No such payment method: " + paymentMethodId);
        this.paymentMethodId = paymentMethodId;
    }

    public String paymentMethodId() {
        return paymentMethodId;
    }
}
