package com.stripeclone.ledger;

/** Thrown when a transaction references an account that does not exist. */
public class AccountNotFoundException extends RuntimeException {

    private final String accountId;

    public AccountNotFoundException(String accountId) {
        super("Account not found: " + accountId);
        this.accountId = accountId;
    }

    public String accountId() {
        return accountId;
    }
}
