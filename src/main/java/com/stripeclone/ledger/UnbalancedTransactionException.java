package com.stripeclone.ledger;

/** Thrown when a set of entries does not satisfy the double-entry invariant. */
public class UnbalancedTransactionException extends RuntimeException {
    public UnbalancedTransactionException(String message) {
        super(message);
    }
}
