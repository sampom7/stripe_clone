package com.stripeclone.payment;

import com.stripeclone.money.Amount;

import java.time.Instant;

public record Charge(
        String chargeId,
        String paymentIntentId,
        Amount amount,
        Amount amountRefunded,
        ChargeStatus status,
        String ledgerTxnId,
        Instant createdAt
) {

    public Amount refundable() {
        return amount.minus(amountRefunded);
    }

    public boolean isFullyRefunded() {
        return amountRefunded.equals(amount);
    }

    public enum ChargeStatus {
        SUCCEEDED("succeeded"),
        REFUNDED("refunded"),
        FAILED("failed");

        private final String wireValue;

        ChargeStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static ChargeStatus fromWire(String value) {
            for (ChargeStatus status : values()) {
                if (status.wireValue.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown charge status: " + value);
        }
    }
}
