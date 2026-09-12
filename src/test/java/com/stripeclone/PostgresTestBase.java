package com.stripeclone;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests, backed by a real Postgres in a container.
 *
 * <p>A real database rather than H2, because the properties under test are database
 * behaviour: {@code SELECT ... FOR UPDATE}, deferred constraint triggers, and rules that
 * block UPDATE on the entries table. H2 emulates none of those faithfully, so a suite
 * built on it would pass while the production behaviour was broken.
 *
 * <p>One container is shared across the whole suite and the tables are truncated between
 * tests. Starting a container per test class would dominate the runtime for no extra
 * isolation.
 */
@SpringBootTest
public abstract class PostgresTestBase {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("stripe_clone_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void truncateLedger() {
        // ledger_entries has rules blocking DELETE, so TRUNCATE is the only way to clear
        // it. That is the append-only guarantee working as intended.
        jdbc.sql("""
                TRUNCATE TABLE ledger_entries, ledger_transactions,
                               account_balances, accounts
                RESTART IDENTITY CASCADE
                """).update();
    }
}
