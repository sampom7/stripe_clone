package com.stripeclone.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stripeclone.payment.PaymentIntent;

/**
 * A payment intent as Stripe serialises it.
 *
 * <p>Field names are snake_case and amounts are integers in the currency's minor unit,
 * which is what the ledger stores anyway, so nothing is converted on the way out.
 * Timestamps are Unix seconds, again matching Stripe rather than ISO-8601.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentIntentResponse(
        String id,
        String object,
        long amount,
        @JsonProperty("amount_capturable") long amountCapturable,
        @JsonProperty("amount_received") long amountReceived,
        String currency,
        String status,
        @JsonProperty("capture_method") String captureMethod,
        String customer,
        String description,
        @JsonProperty("cancellation_reason") String cancellationReason,
        long created
) {

    public static PaymentIntentResponse from(PaymentIntent intent) {
        return new PaymentIntentResponse(
                intent.paymentIntentId(),
                "payment_intent",
                intent.amount().minorUnits(),
                intent.amountCapturable().minorUnits(),
                intent.amountReceived().minorUnits(),
                intent.currency().name().toLowerCase(),
                intent.status().wireValue(),
                intent.captureMethod().wireValue(),
                intent.customerId(),
                intent.description(),
                intent.cancellationReason(),
                intent.createdAt() == null ? 0 : intent.createdAt().getEpochSecond());
    }
}
