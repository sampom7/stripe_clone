package com.stripeclone.money;

/** Thrown when arithmetic is attempted across two different currencies. */
public class CurrencyMismatchException extends RuntimeException {

    private final Currency left;
    private final Currency right;

    public CurrencyMismatchException(Currency left, Currency right) {
        super("Cannot combine amounts in " + left + " and " + right);
        this.left = left;
        this.right = right;
    }

    public Currency left() {
        return left;
    }

    public Currency right() {
        return right;
    }
}
