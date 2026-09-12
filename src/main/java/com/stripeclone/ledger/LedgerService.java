package com.stripeclone.ledger;

import com.stripeclone.money.Amount;
import com.stripeclone.money.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The only write path into the ledger.
 *
 * <p>Everything that moves money in this system goes through {@link #post}. Keeping a
 * single entry point is what makes the invariants checkable: there is one place that
 * locks, one place that validates, and one place that writes. The payment flow, refunds and
 * settlement are all built on top of this rather than beside it.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    /**
     * Account types allowed to hold a negative balance.
     *
     * <p>EXTERNAL represents the world outside the system, so it goes more negative with
     * every deposit; that is the correct behaviour, not an overdraft. Real customer and
     * merchant accounts are floored at zero.
     */
    private static final Set<AccountType> MAY_GO_NEGATIVE = Set.of(AccountType.EXTERNAL);

    private final LedgerRepository repository;

    public LedgerService(LedgerRepository repository) {
        this.repository = repository;
    }

    /**
     * Posts a balanced transaction: locks the accounts, checks the funds, appends the
     * entries, and updates the balance projection, all in one database transaction.
     *
     * <p>Posting the same {@code txnId} twice is a no-op rather than an error. That makes
     * the operation safe to retry, which the idempotency layer in Phase 2 relies on.
     */
    @Transactional
    public LedgerTransaction post(LedgerTransaction txn) {
        if (repository.transactionExists(txn.txnId())) {
            log.debug("Transaction {} already posted; returning existing", txn.txnId());
            return repository.findTransaction(txn.txnId()).orElseThrow();
        }

        // Lock first, in sorted order, so concurrent posts touching the same accounts
        // serialise here rather than racing on the balance check below.
        List<String> accountIds = txn.accountIds();
        for (String accountId : accountIds) {
            if (!repository.accountExists(accountId)) {
                throw new AccountNotFoundException(accountId);
            }
        }
        repository.lockAccounts(accountIds);

        assertSufficientFunds(txn);

        repository.insertTransaction(txn);
        repository.insertEntries(txn);
        repository.applyToBalances(txn);

        log.debug("Posted {} transaction {} for {}", txn.kind(), txn.txnId(), txn.total());
        return txn;
    }

    /**
     * Convenience wrapper for the common two-account movement.
     */
    @Transactional
    public LedgerTransaction transfer(
            String txnId,
            TransactionKind kind,
            String fromAccountId,
            String toAccountId,
            Amount amount,
            String description) {

        return post(LedgerTransaction.transfer(
                txnId, kind, fromAccountId, toAccountId, amount, description));
    }

    @Transactional
    public Account createAccount(String accountId, AccountType type, Currency currency) {
        if (repository.accountExists(accountId)) {
            throw new IllegalArgumentException("Account already exists: " + accountId);
        }
        Account account = new Account(accountId, type, currency, null);
        repository.insertAccount(account);
        log.debug("Created {} account {} in {}", type, accountId, currency);
        return repository.findAccount(accountId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<Account> findAccount(String accountId) {
        return repository.findAccount(accountId);
    }

    /** Reads the balance projection. */
    @Transactional(readOnly = true)
    public Amount balanceOf(String accountId) {
        return repository.findBalance(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /** Recomputes from the entries. The authority the projection is checked against. */
    @Transactional(readOnly = true)
    public Amount recomputedBalanceOf(String accountId) {
        return repository.recomputeBalance(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    @Transactional(readOnly = true)
    public Optional<LedgerTransaction> findTransaction(String txnId) {
        return repository.findTransaction(txnId);
    }

    /**
     * True when the ledger as a whole sums to zero, which it must always do.
     *
     * <p>Exposed so the actuator health check and the test suite can assert the same
     * property rather than each reimplementing it.
     */
    @Transactional(readOnly = true)
    public boolean isBalanced() {
        return repository.sumAllEntries() == 0L;
    }

    /** Accounts where the projection has drifted from the entries. Should always be empty. */
    @Transactional(readOnly = true)
    public List<String> findDriftedAccounts() {
        return repository.findDriftedAccounts();
    }

    /**
     * Rejects any transaction that would take a floored account below zero.
     *
     * <p>Runs after the locks are held, so the balance it reads cannot change underneath it
     * before the entries are written.
     */
    private void assertSufficientFunds(LedgerTransaction txn) {
        for (LedgerEntry entry : txn.entries()) {
            if (!entry.isDebit()) {
                continue;
            }
            Account account = repository.findAccount(entry.accountId())
                    .orElseThrow(() -> new AccountNotFoundException(entry.accountId()));

            if (MAY_GO_NEGATIVE.contains(account.type())) {
                continue;
            }

            Amount current = repository.findBalance(entry.accountId())
                    .orElseThrow(() -> new AccountNotFoundException(entry.accountId()));
            Amount resulting = current.plus(entry.amount());

            if (resulting.isNegative()) {
                throw new InsufficientFundsException(
                        entry.accountId(), current, entry.amount().abs());
            }
        }
    }
}
