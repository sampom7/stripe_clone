package com.stripeclone.money;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A monetary amount as a signed count of minor units plus its currency.
 *
 * <p>{@code Amount.of(1000, USD)} is ten dollars. There is deliberately no floating point
 * and no {@code BigDecimal} anywhere in this type: a {@code long} of minor units cannot
 * represent half a cent, so the question of what to do with a fractional cent never comes
 * up. It is also the representation Stripe uses on the wire, so serialising an amount is
 * just writing the integer out.
 *
 * <p>Amounts are signed. The ledger stores signed entries, where a debit is negative and a
 * credit positive, and requires that the entries of a transaction sum to exactly zero.
 * Rejecting negative amounts here would make that impossible to express.
 *
 * <p>Arithmetic overflows throw rather than wrapping. A silently negated balance is a worse
 * outcome than a failed request.
 */
public record Amount(long minorUnits, Currency currency) implements Comparable<Amount> {

    public Amount {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Amount of(long minorUnits, Currency currency) {
        return new Amount(minorUnits, currency);
    }

    public static Amount of(long minorUnits, String currencyCode) {
        return new Amount(minorUnits, Currency.of(currencyCode));
    }

    public static Amount zero(Currency currency) {
        return new Amount(0L, currency);
    }

    public Amount plus(Amount other) {
        requireSameCurrency(other);
        try {
            return new Amount(Math.addExact(minorUnits, other.minorUnits), currency);
        } catch (ArithmeticException e) {
            throw new ArithmeticException(
                    "Overflow adding " + this + " and " + other);
        }
    }

    public Amount minus(Amount other) {
        requireSameCurrency(other);
        try {
            return new Amount(Math.subtractExact(minorUnits, other.minorUnits), currency);
        } catch (ArithmeticException e) {
            throw new ArithmeticException(
                    "Overflow subtracting " + other + " from " + this);
        }
    }

    public Amount negated() {
        try {
            return new Amount(Math.negateExact(minorUnits), currency);
        } catch (ArithmeticException e) {
            throw new ArithmeticException("Overflow negating " + this);
        }
    }

    public Amount times(long factor) {
        try {
            return new Amount(Math.multiplyExact(minorUnits, factor), currency);
        } catch (ArithmeticException e) {
            throw new ArithmeticException("Overflow multiplying " + this + " by " + factor);
        }
    }

    public Amount abs() {
        return minorUnits < 0 ? negated() : this;
    }

    /**
     * Splits this amount into {@code parts} as evenly as possible, distributing any
     * remainder one minor unit at a time across the leading parts.
     *
     * <p>Splitting 100 three ways gives 34, 33, 33. The parts always sum back to the
     * original: there is no rounding step that can lose or invent a unit. This is the only
     * division offered, precisely because unchecked division is how money goes missing.
     *
     * @throws IllegalArgumentException if {@code parts} is not positive
     */
    public List<Amount> allocate(int parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException("Parts must be positive, got " + parts);
        }
        long base = minorUnits / parts;
        long remainder = minorUnits % parts;

        List<Amount> result = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            long share = base;
            if (remainder != 0) {
                // Push the remainder outward in the direction of the amount's own sign,
                // so a negative amount splits into negative parts.
                long step = remainder > 0 ? 1 : -1;
                share += step;
                remainder -= step;
            }
            result.add(new Amount(share, currency));
        }
        return result;
    }

    /**
     * Splits this amount by integer weights, giving each part a share proportional to its
     * weight and handing any leftover minor units to the largest weights first.
     *
     * <p>This is how a fee split should work: the parts sum exactly back to the original,
     * and the rounding bias is explicit rather than emergent.
     */
    public List<Amount> allocateByWeights(long... weights) {
        if (weights == null || weights.length == 0) {
            throw new IllegalArgumentException("Weights must not be empty");
        }
        long total = 0;
        for (long w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("Weights must not be negative");
            }
            total = Math.addExact(total, w);
        }
        if (total == 0) {
            throw new IllegalArgumentException("Weights must not sum to zero");
        }

        List<Amount> result = new ArrayList<>(weights.length);
        long allocated = 0;
        for (long w : weights) {
            long share = minorUnits * w / total;
            result.add(new Amount(share, currency));
            allocated += share;
        }

        // Distribute the truncation remainder across the parts in order.
        long remainder = minorUnits - allocated;
        int i = 0;
        while (remainder != 0 && i < result.size()) {
            long step = remainder > 0 ? 1 : -1;
            result.set(i, new Amount(result.get(i).minorUnits + step, currency));
            remainder -= step;
            i++;
        }
        return result;
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    public boolean isGreaterThan(Amount other) {
        requireSameCurrency(other);
        return minorUnits > other.minorUnits;
    }

    public boolean isLessThan(Amount other) {
        requireSameCurrency(other);
        return minorUnits < other.minorUnits;
    }

    public boolean isGreaterThanOrEqual(Amount other) {
        requireSameCurrency(other);
        return minorUnits >= other.minorUnits;
    }

    @Override
    public int compareTo(Amount other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    private void requireSameCurrency(Amount other) {
        Objects.requireNonNull(other, "other amount must not be null");
        if (currency != other.currency) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    /** Human-readable form such as {@code 10.00 USD}; never used for arithmetic. */
    @Override
    public String toString() {
        long factor = currency.minorUnitsPerMajor();
        if (factor == 1) {
            return minorUnits + " " + currency;
        }
        long major = minorUnits / factor;
        long minor = Math.abs(minorUnits % factor);
        String sign = (minorUnits < 0 && major == 0) ? "-" : "";
        return String.format("%s%d.%0" + currency.exponent() + "d %s",
                sign, major, minor, currency);
    }
}
