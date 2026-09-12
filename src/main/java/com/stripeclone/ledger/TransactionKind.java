package com.stripeclone.ledger;

/** What a ledger transaction represents. Mirrors the CHECK constraint in V1. */
public enum TransactionKind {
    FUNDING,
    AUTHORIZATION,
    CAPTURE,
    VOID,
    REFUND,
    SETTLEMENT,
    FEE,
    ADJUSTMENT,
    TRANSFER
}
