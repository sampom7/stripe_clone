package com.stripeclone.idempotency;

import com.stripeclone.PostgresTestBase;
import com.stripeclone.common.Ids;
import com.stripeclone.ledger.AccountType;
import com.stripeclone.ledger.LedgerService;
import com.stripeclone.ledger.TransactionKind;
import com.stripeclone.money.Amount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.stripeclone.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyServiceTest extends PostgresTestBase {

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    LedgerService ledger;

    private static final String EXTERNAL = "acct_external_usd";
    private String customer;
    private String merchant;

    @BeforeEach
    void seedAccounts() {
        jdbc.sql("TRUNCATE TABLE idempotency_keys RESTART IDENTITY").update();

        ledger.createAccount(EXTERNAL, AccountType.EXTERNAL, USD);
        customer = Ids.account();
        merchant = Ids.account();
        ledger.createAccount(customer, AccountType.CUSTOMER, USD);
        ledger.createAccount(merchant, AccountType.MERCHANT, USD);
        ledger.transfer(Ids.transaction(), TransactionKind.FUNDING,
                EXTERNAL, customer, Amount.of(100_000, USD), "funding");
    }

    /** A stand-in for a payment response body. */
    record PaymentResponse(String id, long amount, String status) {
    }

    @Test
    @DisplayName("the first request runs the work and stores its response")
    void first_request_executes_the_work() {
        String key = "idem_" + Ids.generate("k");
        AtomicInteger runs = new AtomicInteger();

        var result = idempotency.execute(key, "POST /v1/payment_intents",
                Map.of("amount", 1000), PaymentResponse.class,
                () -> {
                    runs.incrementAndGet();
                    return IdempotencyService.Outcome.created(
                            new PaymentResponse("pi_1", 1000, "succeeded"), "pi_1");
                });

        assertThat(runs.get()).isEqualTo(1);
        assertThat(result.replayed()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.response().id()).isEqualTo("pi_1");

        var stored = idempotency.find(key).orElseThrow();
        assertThat(stored.isCompleted()).isTrue();
        assertThat(stored.resourceId()).isEqualTo("pi_1");
    }

    @Test
    @DisplayName("a repeat request replays the stored response without rerunning the work")
    void repeat_request_replays_without_re_executing() {
        String key = "idem_" + Ids.generate("k");
        Map<String, Object> body = Map.of("amount", 1000, "currency", "usd");
        AtomicInteger runs = new AtomicInteger();

        Callable<PaymentResponse> call = () -> idempotency.execute(
                key, "POST /v1/payment_intents", body, PaymentResponse.class,
                () -> {
                    runs.incrementAndGet();
                    return IdempotencyService.Outcome.created(
                            new PaymentResponse("pi_2", 1000, "succeeded"), "pi_2");
                }).response();

        PaymentResponse first = invoke(call);
        var second = idempotency.execute(key, "POST /v1/payment_intents", body,
                PaymentResponse.class,
                () -> {
                    runs.incrementAndGet();
                    return IdempotencyService.Outcome.created(
                            new PaymentResponse("pi_DIFFERENT", 9999, "failed"), "pi_DIFFERENT");
                });

        assertThat(runs.get()).as("the work ran exactly once").isEqualTo(1);
        assertThat(second.replayed()).isTrue();
        assertThat(second.response()).isEqualTo(first);
        assertThat(second.response().id()).isEqualTo("pi_2");
    }

    @Test
    @DisplayName("the same key with a different body is rejected rather than answered wrongly")
    void same_key_different_body_is_a_conflict() {
        String key = "idem_" + Ids.generate("k");

        idempotency.execute(key, "POST /v1/payment_intents",
                Map.of("amount", 1000), PaymentResponse.class,
                () -> IdempotencyService.Outcome.created(
                        new PaymentResponse("pi_3", 1000, "succeeded"), "pi_3"));

        assertThatThrownBy(() -> idempotency.execute(key, "POST /v1/payment_intents",
                Map.of("amount", 5000), PaymentResponse.class,
                () -> IdempotencyService.Outcome.created(
                        new PaymentResponse("pi_4", 5000, "succeeded"), "pi_4")))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining(key);
    }

    @Test
    @DisplayName("field order and whitespace do not change a request's identity")
    void hashing_is_insensitive_to_key_order() {
        String a = idempotency.hash(Map.of("amount", 1000, "currency", "usd"));
        String b = idempotency.hash(Map.of("currency", "usd", "amount", 1000));

        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("different bodies hash differently")
    void hashing_distinguishes_different_bodies() {
        assertThat(idempotency.hash(Map.of("amount", 1000)))
                .isNotEqualTo(idempotency.hash(Map.of("amount", 1001)));
    }

    @Test
    @DisplayName("a null key means the work simply runs, unprotected")
    void a_null_key_skips_protection() {
        AtomicInteger runs = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            idempotency.execute(null, "POST /v1/payment_intents",
                    Map.of("amount", 1000), PaymentResponse.class,
                    () -> {
                        runs.incrementAndGet();
                        return IdempotencyService.Outcome.created(
                                new PaymentResponse("pi_5", 1000, "succeeded"), "pi_5");
                    });
        }

        assertThat(runs.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("10 concurrent identical requests move money exactly once")
    void concurrent_duplicates_produce_one_ledger_transaction() throws Exception {
        String key = "idem_" + Ids.generate("k");
        Map<String, Object> body = Map.of("amount", 1000, "currency", "usd");

        int threads = 10;
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    var result = idempotency.execute(key, "POST /v1/payment_intents", body,
                            PaymentResponse.class,
                            () -> {
                                // The protected work: an actual movement of money.
                                ledger.transfer(Ids.transaction(), TransactionKind.CAPTURE,
                                        customer, merchant, Amount.of(1000, USD), "idempotent capture");
                                executed.incrementAndGet();
                                return IdempotencyService.Outcome.created(
                                        new PaymentResponse("pi_concurrent", 1000, "succeeded"),
                                        "pi_concurrent");
                            });
                    if (result.replayed()) {
                        replayed.incrementAndGet();
                    }
                    return true;
                } catch (ConcurrentRequestException e) {
                    // Valid: an in-flight winner had not committed yet.
                    rejected.incrementAndGet();
                    return false;
                }
            });
        }

        runConcurrently(tasks);

        assertThat(executed.get())
                .as("the protected work ran exactly once across %d threads", threads)
                .isEqualTo(1);
        assertThat(replayed.get() + rejected.get()).isEqualTo(threads - 1);

        // The real assertion: money moved once, not ten times.
        assertThat(ledger.balanceOf(merchant)).isEqualTo(Amount.of(1000, USD));
        assertThat(ledger.balanceOf(customer)).isEqualTo(Amount.of(99_000, USD));
        assertThat(ledger.isBalanced()).isTrue();
    }

    @Test
    @DisplayName("a failed request leaves no claim behind, so the key can be retried")
    void a_failed_request_releases_its_key() {
        String key = "idem_" + Ids.generate("k");
        Map<String, Object> body = Map.of("amount", 1000);

        assertThatThrownBy(() -> idempotency.execute(key, "POST /v1/payment_intents", body,
                PaymentResponse.class,
                () -> {
                    throw new IllegalStateException("card declined");
                }))
                .isInstanceOf(IllegalStateException.class);

        // The claim rolled back with the work, so the key is free again.
        assertThat(idempotency.find(key)).isEmpty();

        var retry = idempotency.execute(key, "POST /v1/payment_intents", body,
                PaymentResponse.class,
                () -> IdempotencyService.Outcome.created(
                        new PaymentResponse("pi_retry", 1000, "succeeded"), "pi_retry"));

        assertThat(retry.replayed()).isFalse();
        assertThat(retry.response().id()).isEqualTo("pi_retry");
    }

    private <T> T invoke(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private <T> void runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            gate.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            for (Future<T> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
