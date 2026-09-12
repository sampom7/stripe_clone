package com.stripeclone.payment;

public class CustomerNotFoundException extends RuntimeException {

    private final String customerId;

    public CustomerNotFoundException(String customerId) {
        super("No such customer: " + customerId);
        this.customerId = customerId;
    }

    public String customerId() {
        return customerId;
    }
}
