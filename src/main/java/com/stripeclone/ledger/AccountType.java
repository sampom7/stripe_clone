package com.stripeclone.ledger;

/**
 * The kinds of account money can sit in.
 *
 * <p>EXTERNAL is the counterparty for money entering or leaving the system. Funding a
 * customer credits the customer and debits EXTERNAL, so the transaction still balances and
 * money never appears from nowhere. Without it, every deposit would be an unbalanced entry.
 */
public enum AccountType {
    CUSTOMER,
    MERCHANT,
    HOLD,
    SETTLEMENT,
    FEE,
    EXTERNAL
}
