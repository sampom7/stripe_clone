package com.stripeclone.ledger;

import com.stripeclone.money.Amount;
import com.stripeclone.money.Currency;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A balanced set of ledger entries, validated at construction.
 *
 * <p>This is the first of three places the zero-sum invariant is enforced, the others
 * being a deferred constraint trigger in the database and a test that recomputes the whole
 * ledger. The redundancy is deliberate: a ledger that balances only because the
 * application remembered to check is not a ledger.
 *
 * <p>Instances are immutable and cannot exist in an unbalanced state, so any reference to
 * one is a reference to a valid transaction.
 */
public record LedgerTransaction(
        String txnId,
        TransactionKind kind,
        Currency currency,
        String description,
        List<LedgerEntry> entries
) {

    public LedgerTransaction {
        Objects.requireNonNull(txnId, "txnId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(entries, "entries must not be null");

        if (entries.size() < 2) {
            throw new UnbalancedTransactionException(
                    "A double-entry transaction needs at least 2 entries, got " + entries.size());
        }

        entries = List.copyOf(entries);

        Currency resolved = entries.get(0).amount().currency();
        for (LedgerEntry entry : entries) {
            if (entry.amount().currency() != resolved) {
                throw new UnbalancedTransactionException(
                        "Entries mix currencies: " + resolved + " and " + entry.amount().currency());
            }
            if (entry.amount().isZero()) {
                throw new UnbalancedTransactionException(
                        "Zero-amount entry on account " + entry.accountId());
            }
        }

        if (currency == null) {
            currency = resolved;
        } else if (currency != resolved) {
            throw new UnbalancedTransactionException(
                    "Transaction currency " + currency + " does not match entry currency " + resolved);
        }

        long sum = 0;
        for (LedgerEntry entry : entries) {
            sum = Math.addExact(sum, entry.amount().minorUnits());
        }
        if (sum != 0) {
            throw new UnbalancedTransactionException(
                    "Entries sum to " + sum + " minor units, expected 0");
        }
    }

    /** A two-entry transfer: debit one account, credit another, same amount. */
    public static LedgerTransaction transfer(
            String txnId,
            TransactionKind kind,
            String fromAccountId,
            String toAccountId,
            Amount amount,
            String description) {

        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Transfer amount must be positive, got " + amount);
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException(
                    "Cannot transfer from an account to itself: " + fromAccountId);
        }
        return new LedgerTransaction(
                txnId,
                kind,
                amount.currency(),
                description,
                List.of(
                        LedgerEntry.debit(fromAccountId, amount),
                        LedgerEntry.credit(toAccountId, amount)));
    }

    /** Builder for transactions with more than two entries, such as a capture with a fee. */
    public static Builder builder(String txnId, TransactionKind kind) {
        return new Builder(txnId, kind);
    }

    /** Total of the credit entries, which by the invariant equals the total of the debits. */
    public Amount total() {
        long credits = entries.stream()
                .filter(LedgerEntry::isCredit)
                .mapToLong(e -> e.amount().minorUnits())
                .sum();
        return Amount.of(credits, currency);
    }

    /** Every distinct account this transaction touches. */
    public List<String> accountIds() {
        return entries.stream().map(LedgerEntry::accountId).distinct().toList();
    }

    public static final class Builder {
        private final String txnId;
        private final TransactionKind kind;
        private final List<LedgerEntry> entries = new ArrayList<>();
        private String description;

        private Builder(String txnId, TransactionKind kind) {
            this.txnId = txnId;
            this.kind = kind;
        }

        public Builder debit(String accountId, Amount amount) {
            entries.add(LedgerEntry.debit(accountId, amount));
            return this;
        }

        public Builder credit(String accountId, Amount amount) {
            entries.add(LedgerEntry.credit(accountId, amount));
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public LedgerTransaction build() {
            return new LedgerTransaction(txnId, kind, null, description, entries);
        }
    }
}
