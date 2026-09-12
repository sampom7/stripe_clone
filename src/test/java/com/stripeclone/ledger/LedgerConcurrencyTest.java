package com.stripeclone.ledger;

import com.stripeclone.PostgresTestBase;
import com.stripeclone.common.Ids;
import com.stripeclone.money.Amount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.stripeclone.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tests that justify the whole design.
 *
 * <p>A ledger that is correct single-threaded is not interesting; every naive
 * implementation is. These drive real concurrent load through a real Postgres and assert
 * that money is neither created nor destroyed.
 */
class LedgerConcurrencyTest extends PostgresTestBase {

    @Autowired
    LedgerService ledger;

    private static final String EXTERNAL = "acct_external_usd";

    private void ensureExternal() {
        if (ledger.findAccount(EXTERNAL).isEmpty()) {
            ledger.createAccount(EXTERNAL, AccountType.EXTERNAL, USD);
        }
    }

    private String account(AccountType type, long funding) {
        ensureExternal();
        String accountId = Ids.account();
        ledger.createAccount(accountId, type, USD);
        if (funding > 0) {
            ledger.transfer(Ids.transaction(), TransactionKind.FUNDING,
                    EXTERNAL, accountId, Amount.of(funding, USD), "funding");
        }
        return accountId;
    }

    /** Runs every task at once and waits for all of them. */
    private <T> List<Future<T>> runConcurrently(List<Callable<T>> tasks) throws Exception {
        int n = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(n);

        try {
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    startGate.await();          // release all threads together
                    return task.call();
                }));
            }
            startGate.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
                    .as("all concurrent tasks finished")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
        return futures;
    }

    @Test
    @DisplayName("20 concurrent debits on one account lose no updates")
    void concurrent_debits_do_not_lose_updates() throws Exception {
        int threads = 20;
        long each = 100;
        String source = account(AccountType.CUSTOMER, threads * each);
        String sink = account(AccountType.MERCHANT, 0);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ledger.transfer(Ids.transaction(), TransactionKind.TRANSFER,
                        source, sink, Amount.of(each, USD), "concurrent debit");
                return true;
            });
        }

        List<Future<Boolean>> futures = runConcurrently(tasks);
        for (Future<Boolean> f : futures) {
            f.get();    // surface any failure
        }

        // If a single update were lost, the source would still hold money and the
        // destination would be short. Both must be exact.
        assertThat(ledger.balanceOf(source)).isEqualTo(Amount.zero(USD));
        assertThat(ledger.balanceOf(sink)).isEqualTo(Amount.of(threads * each, USD));
        assertThat(ledger.recomputedBalanceOf(sink)).isEqualTo(ledger.balanceOf(sink));
        assertThat(ledger.isBalanced()).isTrue();
        assertThat(ledger.findDriftedAccounts()).isEmpty();
    }

    @Test
    @DisplayName("concurrent overdraft attempts: only the affordable ones succeed")
    void concurrent_overdrafts_cannot_drive_a_balance_negative() throws Exception {
        // Fund for exactly 5 transfers, then have 20 threads race for them.
        int threads = 20;
        long each = 100;
        String source = account(AccountType.CUSTOMER, 5 * each);
        String sink = account(AccountType.MERCHANT, 0);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    ledger.transfer(Ids.transaction(), TransactionKind.TRANSFER,
                            source, sink, Amount.of(each, USD), "race");
                    succeeded.incrementAndGet();
                    return true;
                } catch (InsufficientFundsException e) {
                    rejected.incrementAndGet();
                    return false;
                }
            });
        }

        runConcurrently(tasks);

        assertThat(succeeded.get())
                .as("exactly the funded number of transfers went through")
                .isEqualTo(5);
        assertThat(rejected.get()).isEqualTo(threads - 5);

        assertThat(ledger.balanceOf(source)).isEqualTo(Amount.zero(USD));
        assertThat(ledger.balanceOf(source).isNegative()).isFalse();
        assertThat(ledger.balanceOf(sink)).isEqualTo(Amount.of(500, USD));
        assertThat(ledger.isBalanced()).isTrue();
    }

    @Test
    @DisplayName("transfers in opposite directions between the same pair do not deadlock")
    void opposing_transfers_do_not_deadlock() throws Exception {
        // Without sorted lock ordering this is the classic ABBA deadlock: one thread locks
        // A then B while the other locks B then A. Postgres would detect it and abort a
        // transaction, so failures here would be intermittent rather than obvious.
        String a = account(AccountType.CUSTOMER, 100_000);
        String b = account(AccountType.CUSTOMER, 100_000);

        int pairs = 15;
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            tasks.add(() -> {
                ledger.transfer(Ids.transaction(), TransactionKind.TRANSFER,
                        a, b, Amount.of(10, USD), "a to b");
                return true;
            });
            tasks.add(() -> {
                ledger.transfer(Ids.transaction(), TransactionKind.TRANSFER,
                        b, a, Amount.of(10, USD), "b to a");
                return true;
            });
        }

        List<Future<Boolean>> futures = runConcurrently(tasks);
        for (Future<Boolean> f : futures) {
            f.get();    // a deadlock abort would surface here
        }

        // Equal traffic both ways leaves both balances where they started.
        assertThat(ledger.balanceOf(a)).isEqualTo(Amount.of(100_000, USD));
        assertThat(ledger.balanceOf(b)).isEqualTo(Amount.of(100_000, USD));
        assertThat(ledger.isBalanced()).isTrue();
        assertThat(ledger.findDriftedAccounts()).isEmpty();
    }

    @Test
    @DisplayName("concurrent posts of the same transaction id apply it once")
    void duplicate_transaction_ids_are_idempotent_under_races() throws Exception {
        String source = account(AccountType.CUSTOMER, 10_000);
        String sink = account(AccountType.MERCHANT, 0);
        String sharedTxnId = Ids.transaction();

        int threads = 10;
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    ledger.transfer(sharedTxnId, TransactionKind.TRANSFER,
                            source, sink, Amount.of(1000, USD), "duplicate");
                    return true;
                } catch (RuntimeException e) {
                    // A unique-constraint loser is an acceptable outcome; what matters is
                    // that the money moved exactly once.
                    return false;
                }
            });
        }

        runConcurrently(tasks);

        assertThat(ledger.balanceOf(sink))
                .as("the transfer applied exactly once despite %d concurrent posts", threads)
                .isEqualTo(Amount.of(1000, USD));
        assertThat(ledger.balanceOf(source)).isEqualTo(Amount.of(9000, USD));
        assertThat(ledger.isBalanced()).isTrue();
    }
}
