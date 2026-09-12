package com.stripeclone.ledger;

import com.stripeclone.money.Amount;
import com.stripeclone.money.Currency;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * All ledger SQL, written by hand so that locking and transaction boundaries stay visible.
 *
 * <p>The one rule worth knowing before reading further: whenever multiple accounts are
 * locked, they are locked in sorted order by account id. See {@link #lockAccounts}.
 */
@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- accounts

    public void insertAccount(Account account) {
        jdbc.sql("""
                INSERT INTO accounts (account_id, account_type, currency)
                VALUES (:accountId, :accountType, :currency)
                """)
                .param("accountId", account.accountId())
                .param("accountType", account.type().name())
                .param("currency", account.currency().name())
                .update();

        jdbc.sql("""
                INSERT INTO account_balances (account_id, balance, currency)
                VALUES (:accountId, 0, :currency)
                """)
                .param("accountId", account.accountId())
                .param("currency", account.currency().name())
                .update();
    }

    public Optional<Account> findAccount(String accountId) {
        return jdbc.sql("""
                SELECT account_id, account_type, currency, created_at
                  FROM accounts
                 WHERE account_id = :accountId
                """)
                .param("accountId", accountId)
                .query(LedgerRepository::mapAccount)
                .optional();
    }

    public boolean accountExists(String accountId) {
        Integer found = jdbc.sql("SELECT 1 FROM accounts WHERE account_id = :accountId")
                .param("accountId", accountId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        return found != null;
    }

    /**
     * Takes a row lock on each account, in ascending account-id order.
     *
     * <p>The ordering is the entire point. Two concurrent transfers between the same pair
     * of accounts in opposite directions will deadlock if each locks its own "from" account
     * first: each holds what the other needs. Sorting the ids means both transactions
     * queue for the same row first, and one simply waits. Postgres would detect the
     * deadlock and abort a transaction, so the bug shows up as intermittent failures under
     * load rather than corruption, which makes it exactly the kind of bug that survives
     * into production.
     *
     * <p>Locking also serialises balance reads against concurrent writers, which is what
     * makes the read-check-write in a transfer safe.
     */
    public void lockAccounts(List<String> accountIds) {
        List<String> ordered = accountIds.stream().distinct().sorted().toList();
        if (ordered.isEmpty()) {
            return;
        }
        jdbc.sql("""
                SELECT account_id
                  FROM accounts
                 WHERE account_id IN (:accountIds)
                 ORDER BY account_id
                   FOR UPDATE
                """)
                .param("accountIds", ordered)
                .query(String.class)
                .list();
    }

    // ------------------------------------------------------------ transactions

    public void insertTransaction(LedgerTransaction txn) {
        jdbc.sql("""
                INSERT INTO ledger_transactions (txn_id, kind, currency, description)
                VALUES (:txnId, :kind, :currency, :description)
                """)
                .param("txnId", txn.txnId())
                .param("kind", txn.kind().name())
                .param("currency", txn.currency().name())
                .param("description", txn.description())
                .update();
    }

    public void insertEntries(LedgerTransaction txn) {
        for (LedgerEntry entry : txn.entries()) {
            jdbc.sql("""
                    INSERT INTO ledger_entries (txn_id, account_id, amount, currency)
                    VALUES (:txnId, :accountId, :amount, :currency)
                    """)
                    .param("txnId", txn.txnId())
                    .param("accountId", entry.accountId())
                    .param("amount", entry.amount().minorUnits())
                    .param("currency", entry.amount().currency().name())
                    .update();
        }
    }

    public boolean transactionExists(String txnId) {
        Integer found = jdbc.sql("SELECT 1 FROM ledger_transactions WHERE txn_id = :txnId")
                .param("txnId", txnId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        return found != null;
    }

    public Optional<LedgerTransaction> findTransaction(String txnId) {
        var header = jdbc.sql("""
                SELECT txn_id, kind, currency, description
                  FROM ledger_transactions
                 WHERE txn_id = :txnId
                """)
                .param("txnId", txnId)
                .query((ResultSet rs, int rowNum) -> new Object[]{
                        rs.getString("txn_id"),
                        TransactionKind.valueOf(rs.getString("kind")),
                        Currency.of(rs.getString("currency")),
                        rs.getString("description")})
                .optional();

        if (header.isEmpty()) {
            return Optional.empty();
        }
        Object[] h = header.get();
        Currency currency = (Currency) h[2];

        List<LedgerEntry> entries = jdbc.sql("""
                SELECT account_id, amount
                  FROM ledger_entries
                 WHERE txn_id = :txnId
                 ORDER BY id
                """)
                .param("txnId", txnId)
                .query((ResultSet rs, int rowNum) -> new LedgerEntry(
                        rs.getString("account_id"),
                        Amount.of(rs.getLong("amount"), currency)))
                .list();

        return Optional.of(new LedgerTransaction(
                (String) h[0], (TransactionKind) h[1], currency, (String) h[3], entries));
    }

    // --------------------------------------------------------------- balances

    /** Reads the projection. Cheap; used on the read path. */
    public Optional<Amount> findBalance(String accountId) {
        return jdbc.sql("""
                SELECT balance, currency
                  FROM account_balances
                 WHERE account_id = :accountId
                """)
                .param("accountId", accountId)
                .query((ResultSet rs, int rowNum) ->
                        Amount.of(rs.getLong("balance"), Currency.of(rs.getString("currency"))))
                .optional();
    }

    /**
     * Recomputes a balance by summing the entries themselves.
     *
     * <p>This is the authority the projection is checked against. It is deliberately not
     * used on the hot path, only in reconciliation and in tests.
     */
    public Optional<Amount> recomputeBalance(String accountId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(e.amount), 0) AS balance, a.currency AS currency
                  FROM accounts a
                  LEFT JOIN ledger_entries e ON e.account_id = a.account_id
                 WHERE a.account_id = :accountId
                 GROUP BY a.currency
                """)
                .param("accountId", accountId)
                .query((ResultSet rs, int rowNum) ->
                        Amount.of(rs.getLong("balance"), Currency.of(rs.getString("currency"))))
                .optional();
    }

    /** Applies a transaction's entries to the projection. Must run in the same transaction. */
    public void applyToBalances(LedgerTransaction txn) {
        for (LedgerEntry entry : txn.entries()) {
            int updated = jdbc.sql("""
                    UPDATE account_balances
                       SET balance = balance + :delta,
                           updated_at = now()
                     WHERE account_id = :accountId
                    """)
                    .param("delta", entry.amount().minorUnits())
                    .param("accountId", entry.accountId())
                    .update();

            if (updated != 1) {
                throw new IllegalStateException(
                        "No balance row for account " + entry.accountId()
                                + "; account must be created before it is used");
            }
        }
    }

    /** Sum of every entry in the ledger. Must always be zero. */
    public long sumAllEntries() {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries")
                .query(Long.class)
                .single();
    }

    /** Accounts where the projection disagrees with the recomputed sum. Should be empty. */
    public List<String> findDriftedAccounts() {
        return jdbc.sql("""
                SELECT b.account_id
                  FROM account_balances b
                  LEFT JOIN (
                        SELECT account_id, SUM(amount) AS total
                          FROM ledger_entries
                         GROUP BY account_id
                  ) e ON e.account_id = b.account_id
                 WHERE b.balance <> COALESCE(e.total, 0)
                """)
                .query(String.class)
                .list();
    }

    private static Account mapAccount(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getString("account_id"),
                AccountType.valueOf(rs.getString("account_type")),
                Currency.of(rs.getString("currency")),
                rs.getTimestamp("created_at").toInstant());
    }
}
