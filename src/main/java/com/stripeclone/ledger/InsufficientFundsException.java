package com.stripeclone.ledger;

import com.stripeclone.money.Amount;

/** Thrown when a debit would take an account below the balance floor it is allowed. */
public class InsufficientFundsException extends RuntimeException {

    private final String accountId;
    private final Amount available;
    private final Amount requested;

    public InsufficientFundsException(String accountId, Amount available, Amount requested) {
        super("Account " + accountId + " has " + available + " available but " + requested
                + " was requested");
        this.accountId = accountId;
        this.available = available;
        this.requested = requested;
    }

    public String accountId() {
        return accountId;
    }

    public Amount available() {
        return available;
    }

    public Amount requested() {
        return requested;
    }
}
