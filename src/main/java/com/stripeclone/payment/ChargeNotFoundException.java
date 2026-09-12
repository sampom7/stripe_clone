package com.stripeclone.payment;

public class ChargeNotFoundException extends RuntimeException {

    private final String chargeId;

    public ChargeNotFoundException(String chargeId) {
        super("No such charge: " + chargeId);
        this.chargeId = chargeId;
    }

    public String chargeId() {
        return chargeId;
    }
}
