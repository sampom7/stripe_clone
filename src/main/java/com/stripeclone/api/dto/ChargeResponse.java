package com.stripeclone.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stripeclone.payment.Charge;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChargeResponse(
        String id,
        String object,
        long amount,
        @JsonProperty("amount_refunded") long amountRefunded,
        String currency,
        String status,
        boolean refunded,
        @JsonProperty("payment_intent") String paymentIntent,
        long created
) {

    public static ChargeResponse from(Charge charge) {
        return new ChargeResponse(
                charge.chargeId(),
                "charge",
                charge.amount().minorUnits(),
                charge.amountRefunded().minorUnits(),
                charge.amount().currency().name().toLowerCase(),
                charge.status().wireValue(),
                charge.isFullyRefunded(),
                charge.paymentIntentId(),
                charge.createdAt() == null ? 0 : charge.createdAt().getEpochSecond());
    }
}
