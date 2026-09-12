package com.stripeclone.payment;

import java.time.Instant;

/**
 * A stored payment method.
 *
 * <p>Only the parts that survive tokenisation: brand, last four, expiry. The number itself
 * is never persisted.
 */
public record PaymentMethod(
        String paymentMethodId,
        String customerId,
        String type,
        String brand,
        String last4,
        Integer expMonth,
        Integer expYear,
        String fingerprint,
        Instant createdAt
) {

    public boolean isAttached() {
        return customerId != null;
    }
}
