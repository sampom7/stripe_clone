package com.stripeclone.outbox;

/** Event names, matching Stripe's. These are what webhook endpoints subscribe to. */
public final class EventType {

    public static final String PAYMENT_INTENT_CREATED = "payment_intent.created";
    public static final String PAYMENT_INTENT_SUCCEEDED = "payment_intent.succeeded";
    public static final String PAYMENT_INTENT_CANCELED = "payment_intent.canceled";
    public static final String PAYMENT_INTENT_AMOUNT_CAPTURABLE_UPDATED =
            "payment_intent.amount_capturable_updated";
    public static final String CHARGE_SUCCEEDED = "charge.succeeded";
    public static final String CHARGE_REFUNDED = "charge.refunded";
    public static final String CUSTOMER_CREATED = "customer.created";

    private EventType() {
    }
}
