package com.stripeclone.payment;

import com.stripeclone.money.Amount;

import java.time.Instant;

public record Refund(
        String refundId,
        String chargeId,
        Amount amount,
        String reason,
        RefundStatus status,
        String ledgerTxnId,
        Instant createdAt
) {

    public enum RefundStatus {
        SUCCEEDED("succeeded"),
        FAILED("failed"),
        CANCELED("canceled");

        private final String wireValue;

        RefundStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static RefundStatus fromWire(String value) {
            for (RefundStatus status : values()) {
                if (status.wireValue.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown refund status: " + value);
        }
    }
}
