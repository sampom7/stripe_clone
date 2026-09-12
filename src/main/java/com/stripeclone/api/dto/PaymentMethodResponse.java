package com.stripeclone.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stripeclone.payment.PaymentMethod;

import java.util.Map;

/**
 * A payment method, shaped like Stripe's: the card details sit under a nested "card"
 * object rather than being flattened onto the top level.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentMethodResponse(
        String id,
        String object,
        String type,
        Map<String, Object> card,
        String customer,
        long created
) {

    public static PaymentMethodResponse from(PaymentMethod method) {
        Map<String, Object> card = Map.of(
                "brand", method.brand() == null ? "unknown" : method.brand(),
                "last4", method.last4() == null ? "" : method.last4(),
                "exp_month", method.expMonth() == null ? 0 : method.expMonth(),
                "exp_year", method.expYear() == null ? 0 : method.expYear(),
                "fingerprint", method.fingerprint() == null ? "" : method.fingerprint());

        return new PaymentMethodResponse(
                method.paymentMethodId(),
                "payment_method",
                method.type(),
                card,
                method.customerId(),
                method.createdAt() == null ? 0 : method.createdAt().getEpochSecond());
    }
}
