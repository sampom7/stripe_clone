package com.stripeclone.ledger;

import com.stripeclone.PostgresTestBase;
import com.stripeclone.common.Ids;
import com.stripeclone.money.Amount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Random;

import static com.stripeclone.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServiceTest extends PostgresTestBase {

    @Autowired
    LedgerService ledger;

    private static final Amount TEN_DOLLARS = Amount.of(1000, USD);

    /** Creates an account and funds it from EXTERNAL, so the ledger stays balanced. */
    private String fundedAccount(AccountType type, long minorUnits) {
        String external = "acct_external_usd";
        if (ledger.findAccount(external).isEmpty()) {
            ledger.createAccount(external, AccountType.EXTERNAL, USD);
        }
        String accountId = Ids.account();
        ledger.createAccount(accountId, type, USD);

        if (minorUnits > 0) {
            ledger.transfer(Ids.transaction(), TransactionKind.FUNDING,
                    external, accountId, Amount.of(minorUnits, USD), "test funding");
        }
        return accountId;
    }

    @Nested
    @DisplayName("a posted transaction")
    class Posting {

        @Test
        void moves_money_and_updates_both_balances() {
            String from = fundedAccount(AccountType.CUSTOMER, 5000);
            String to = fundedAccount(AccountType.MERCHANT, 0);

            ledger.transfer(Ids.transaction(), TransactionKind.TRANSFER,
                    from, to, TEN_DOLLARS, "a transfer");

            assertThat(ledger.balanceOf(from)).isEqualTo(Amount.of(4000, USD));
            assertThat(ledger.balanceOf(to)).isEqualTo(TEN_DOLLARS);
        }

        @Test
        void is_rejected_by_the_domain_when_entries_do_not_sum_to_zero() {
            assertThatThrownBy(() -> new LedgerTransaction(
                    Ids.transaction(),
                    TransactionKind.TRANSFER,
                    USD,
                    "deliberately unbalanced",
                    List.of(
                            LedgerEntry.debit("a", Amount.of(1000, USD)),
                            LedgerEntry.credit("b", Amount.of(999, USD)))))
                    .isInstanceOf(UnbalancedTransactionException.class)
                    .hasMessageContaining("sum to");
        }

        @Test
        void is_rejected_when_it_has_only_one_entry() {
            assertThatThrownBy(() -> new LedgerTransaction(
                    Ids.transaction(), TransactionKind.TRANSFER, USD, "one-sided",
                    List.of(LedgerEntry.debit("a", TEN_DOLLARS))))
                    .isInstanceOf(UnbalancedTransactionException.class)
                    .hasMessageContaining("at least 2");
        }

        @Test
        void is_rejected_when_it_would_overdraw_a_customer_account() {
            String from = fundedAccount(AccountType.CUSTOMER, 500);
            String to = fundedAccount(AccountType.MERCHANT, 0);

            assertThatThrownBy(() -> ledger.transfer(Ids.transaction(),
                    TransactionKind.TRANSFER, from, to, TEN_DOLLARS, "too much"))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(ledger.balanceOf(from)).isEqualTo(Amount.of(500, USD));
            assertThat(ledger.balanceOf(to)).isEqualTo(Amount.zero(USD));
        }

        @Test
        void is_allowed_to_take_the_external_account_negative() {
            // EXTERNAL is the outside world; it goes more negative with every deposit.
            String customer = fundedAccount(AccountType.CUSTOMER, 10_000);

            assertThat(ledger.balanceOf("acct_external_usd").isNegative()).isTrue();
            assertThat(ledger.balanceOf(customer)).isEqualTo(Amount.of(10_000, USD));
        }

        @Test
        void is_rejected_when_an_account_does_not_exist() {
            String real = fundedAccount(AccountType.CUSTOMER, 5000);

            assertThatThrownBy(() -> ledger.transfer(Ids.transaction(),
                    TransactionKind.TRANSFER, real, "acct_does_not_exist", TEN_DOLLARS, "nope"))
                    .isInstanceOf(AccountNotFoundException.class);
        }

        @Test
        void posting_the_same_transaction_id_twice_is_a_no_op() {
            String from = fundedAccount(AccountType.CUSTOMER, 5000);
            String to = fundedAccount(AccountType.MERCHANT, 0);
            String txnId = Ids.transaction();

            ledger.transfer(txnId, TransactionKind.TRANSFER, from, to, TEN_DOLLARS, "once");
            ledger.transfer(txnId, TransactionKind.TRANSFER, from, to, TEN_DOLLARS, "again");

            // Money moved exactly once, not twice.
            assertThat(ledger.balanceOf(from)).isEqualTo(Amount.of(4000, USD));
            assertThat(ledger.balanceOf(to)).isEqualTo(TEN_DOLLARS);
        }
    }

    @Nested
    @DisplayName("the ledger as a whole")
    class Invariants {

        @Test
        void sums_to_zero_after_many_random_transfers() {
            List<String> accounts = List.of(
                    fundedAccount(AccountType.CUSTOMER, 100_000),
                    fundedAccount(AccountType.CUSTOMER, 100_000),
                    fundedAccount(AccountType.MERCHANT, 100_000),
                    fundedAccount(AccountType.MERCHANT, 100_000));

            Random random = new Random(20250912L);
            int applied = 0;

            for (int i = 0; i < 200; i++) {
                String from = accounts.get(random.nextInt(accounts.size()));
                String to = accounts.get(random.nextInt(accounts.size()));
                if (from.equals(to)) {
                    continue;
                }
                Amount amount = Amount.of(1 + random.nextInt(5000), USD);
                try {
                    ledger.transfer(Ids.transaction(), TransactionKind.TRANSFER,
                            from, to, amount, "random " + i);
                    applied++;
                } catch (InsufficientFundsException expected) {
                    // A rejected transfer is a valid outcome; the ledger must still balance.
                }
            }

            assertThat(applied).isGreaterThan(50);
            assertThat(ledger.isBalanced())
                    .as("ledger sums to zero after %d transfers", applied)
                    .isTrue();
            assertThat(ledger.findDriftedAccounts()).isEmpty();
        }

        @Test
        void keeps_the_balance_projection_equal_to_the_recomputed_sum() {
            String from = fundedAccount(AccountType.CUSTOMER, 50_000);
            String to = fundedAccount(AccountType.MERCHANT, 0);

            for (int i = 0; i < 25; i++) {
                ledger.transfer(Ids.transaction(), TransactionKind.TRANSFER,
                        from, to, Amount.of(137, USD), "drift check " + i);
            }

            assertThat(ledger.balanceOf(from)).isEqualTo(ledger.recomputedBalanceOf(from));
            assertThat(ledger.balanceOf(to)).isEqualTo(ledger.recomputedBalanceOf(to));
            assertThat(ledger.findDriftedAccounts()).isEmpty();
        }

        @Test
        void never_records_a_failed_transfer() {
            String from = fundedAccount(AccountType.CUSTOMER, 100);
            String to = fundedAccount(AccountType.MERCHANT, 0);
            String txnId = Ids.transaction();

            assertThatThrownBy(() -> ledger.transfer(txnId, TransactionKind.TRANSFER,
                    from, to, TEN_DOLLARS, "will fail"))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(ledger.findTransaction(txnId)).isEmpty();
            assertThat(ledger.isBalanced()).isTrue();
        }
    }

    @Nested
    @DisplayName("multi-entry transactions")
    class MultiEntry {

        @Test
        void supports_a_split_across_three_accounts() {
            String customer = fundedAccount(AccountType.CUSTOMER, 10_000);
            String merchant = fundedAccount(AccountType.MERCHANT, 0);
            String fees = fundedAccount(AccountType.FEE, 0);

            // A 1000 payment where the platform keeps 29 + 30 = 59 in fees.
            LedgerTransaction txn = LedgerTransaction.builder(Ids.transaction(), TransactionKind.CAPTURE)
                    .debit(customer, Amount.of(1000, USD))
                    .credit(merchant, Amount.of(941, USD))
                    .credit(fees, Amount.of(59, USD))
                    .description("capture with fee")
                    .build();

            ledger.post(txn);

            assertThat(ledger.balanceOf(customer)).isEqualTo(Amount.of(9000, USD));
            assertThat(ledger.balanceOf(merchant)).isEqualTo(Amount.of(941, USD));
            assertThat(ledger.balanceOf(fees)).isEqualTo(Amount.of(59, USD));
            assertThat(ledger.isBalanced()).isTrue();
        }

        @Test
        void reports_the_transaction_total_as_the_credit_side() {
            LedgerTransaction txn = LedgerTransaction.builder(Ids.transaction(), TransactionKind.CAPTURE)
                    .debit("a", Amount.of(1000, USD))
                    .credit("b", Amount.of(941, USD))
                    .credit("c", Amount.of(59, USD))
                    .build();

            assertThat(txn.total()).isEqualTo(Amount.of(1000, USD));
            assertThat(txn.accountIds()).containsExactlyInAnyOrder("a", "b", "c");
        }
    }

    @Nested
    @DisplayName("the append-only guarantee")
    class AppendOnly {

        @Test
        void silently_discards_attempts_to_update_or_delete_entries() {
            String from = fundedAccount(AccountType.CUSTOMER, 5000);
            String to = fundedAccount(AccountType.MERCHANT, 0);
            ledger.transfer(Ids.transaction(), TransactionKind.TRANSFER,
                    from, to, TEN_DOLLARS, "immutable");

            jdbc.sql("UPDATE ledger_entries SET amount = 999999").update();
            jdbc.sql("DELETE FROM ledger_entries").update();

            // The rules turned both statements into no-ops, so history is intact.
            assertThat(ledger.balanceOf(to)).isEqualTo(TEN_DOLLARS);
            assertThat(ledger.recomputedBalanceOf(to)).isEqualTo(TEN_DOLLARS);
            assertThat(ledger.isBalanced()).isTrue();
        }
    }
}
