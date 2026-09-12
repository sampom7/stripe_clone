package com.stripeclone.ledger;

import com.stripeclone.money.Amount;

/**
 * One side of a ledger transaction: a signed amount applied to one account.
 *
 * <p>Negative is a debit, positive a credit. Using one signed field rather than a separate
 * DEBIT/CREDIT enum plus a magnitude means the balance check is a plain SUM, and there is
 * no way to express the contradictory state of a "negative debit".
 */
public record LedgerEntry(String accountId, Amount amount) {

    public static LedgerEntry debit(String accountId, Amount amount) {
        requirePositive(amount);
        return new LedgerEntry(accountId, amount.negated());
    }

    public static LedgerEntry credit(String accountId, Amount amount) {
        requirePositive(amount);
        return new LedgerEntry(accountId, amount);
    }

    public boolean isDebit() {
        return amount.isNegative();
    }

    public boolean isCredit() {
        return amount.isPositive();
    }

    private static void requirePositive(Amount amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "Debit and credit take a positive magnitude, got " + amount);
        }
    }
}
