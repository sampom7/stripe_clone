package com.stripeclone.money;

import java.util.Arrays;

/**
 * ISO-4217 currencies with the exponent that defines their minor unit.
 *
 * <p>The exponent is the number of decimal places the currency has, which fixes how many
 * minor units make one major unit. USD has exponent 2, so 100 cents make a dollar. JPY has
 * exponent 0, so the yen has no subdivision at all. This matters because a hard-coded
 * factor of 100 silently corrupts every yen amount in the system.
 */
public enum Currency {

    USD(2),
    EUR(2),
    GBP(2),
    INR(2),
    JPY(0);

    private final int exponent;

    Currency(int exponent) {
        this.exponent = exponent;
    }

    /** Number of decimal places; 2 for USD, 0 for JPY. */
    public int exponent() {
        return exponent;
    }

    /** Minor units in one major unit: 100 for USD, 1 for JPY. */
    public long minorUnitsPerMajor() {
        long factor = 1;
        for (int i = 0; i < exponent; i++) {
            factor *= 10;
        }
        return factor;
    }

    public static Currency of(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Currency code must not be null");
        }
        String upper = code.toUpperCase();
        return Arrays.stream(values())
                .filter(c -> c.name().equals(upper))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported currency: " + code));
    }
}
