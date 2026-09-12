package com.stripeclone.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stripeclone.payment.Refund;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundResponse(
        String id,
        String object,
        long amount,
        String currency,
        String charge,
        String reason,
        String status,
        long created
) {

    public static RefundResponse from(Refund refund) {
        return new RefundResponse(
                refund.refundId(),
                "refund",
                refund.amount().minorUnits(),
                refund.amount().currency().name().toLowerCase(),
                refund.chargeId(),
                refund.reason(),
                refund.status().wireValue(),
                refund.createdAt() == null ? 0 : refund.createdAt().getEpochSecond());
    }
}
