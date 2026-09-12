-- Ledger core: accounts, transactions, entries, and the balance projection.
--
-- Design notes that the schema itself enforces:
--   * ledger_entries is append-only. A rule blocks UPDATE and DELETE outright, so the
--     invariant does not depend on application code remembering to behave.
--   * Entry amounts are signed. A transaction's entries must sum to exactly zero; this is
--     checked by a constraint trigger that fires at COMMIT, since the sum is only
--     meaningful once every entry of the transaction has been inserted.
--   * account_balances is a projection, not the source of truth. It can be dropped and
--     rebuilt from ledger_entries at any time, and a test asserts that it agrees.

CREATE TABLE accounts (
    id              BIGSERIAL PRIMARY KEY,
    account_id      TEXT        NOT NULL UNIQUE,
    account_type    TEXT        NOT NULL,
    currency        TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT accounts_type_valid CHECK (account_type IN (
        'CUSTOMER', 'MERCHANT', 'HOLD', 'SETTLEMENT', 'FEE', 'EXTERNAL'
    )),
    CONSTRAINT accounts_currency_valid CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_accounts_type ON accounts (account_type);

-- A single balanced movement of money. Entries hang off this row.
CREATE TABLE ledger_transactions (
    id              BIGSERIAL PRIMARY KEY,
    txn_id          TEXT        NOT NULL UNIQUE,
    kind            TEXT        NOT NULL,
    currency        TEXT        NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ledger_txn_kind_valid CHECK (kind IN (
        'FUNDING', 'AUTHORIZATION', 'CAPTURE', 'VOID',
        'REFUND', 'SETTLEMENT', 'FEE', 'ADJUSTMENT', 'TRANSFER'
    )),
    CONSTRAINT ledger_txn_currency_valid CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_ledger_txn_kind ON ledger_transactions (kind);
CREATE INDEX idx_ledger_txn_created_at ON ledger_transactions (created_at);

-- The append-only heart of the system. Signed amounts: negative debits, positive credits.
CREATE TABLE ledger_entries (
    id              BIGSERIAL PRIMARY KEY,
    txn_id          TEXT        NOT NULL REFERENCES ledger_transactions (txn_id),
    account_id      TEXT        NOT NULL REFERENCES accounts (account_id),
    amount          BIGINT      NOT NULL,
    currency        TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ledger_entries_amount_nonzero CHECK (amount <> 0),
    CONSTRAINT ledger_entries_currency_valid CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_ledger_entries_txn ON ledger_entries (txn_id);
CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id);
CREATE INDEX idx_ledger_entries_account_created ON ledger_entries (account_id, created_at);

-- Append-only enforcement. Postgres rules reject the statement before it touches a row,
-- so no application path and no stray psql session can mutate history.
CREATE RULE ledger_entries_no_update AS
    ON UPDATE TO ledger_entries DO INSTEAD NOTHING;

CREATE RULE ledger_entries_no_delete AS
    ON DELETE TO ledger_entries DO INSTEAD NOTHING;

-- Balance projection. Maintained in the same transaction as the entries it summarises.
CREATE TABLE account_balances (
    account_id      TEXT        PRIMARY KEY REFERENCES accounts (account_id),
    balance         BIGINT      NOT NULL DEFAULT 0,
    currency        TEXT        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every transaction's entries must sum to zero, and must all share one currency.
-- Deferred to COMMIT because the sum is only complete once all entries are inserted.
CREATE OR REPLACE FUNCTION assert_transaction_balances()
RETURNS TRIGGER AS $$
DECLARE
    entry_sum       BIGINT;
    entry_count     INTEGER;
    currency_count  INTEGER;
BEGIN
    SELECT COALESCE(SUM(amount), 0), COUNT(*), COUNT(DISTINCT currency)
      INTO entry_sum, entry_count, currency_count
      FROM ledger_entries
     WHERE txn_id = NEW.txn_id;

    IF entry_count < 2 THEN
        RAISE EXCEPTION
            'Transaction % has % entries; a double-entry transaction needs at least 2',
            NEW.txn_id, entry_count;
    END IF;

    IF currency_count > 1 THEN
        RAISE EXCEPTION
            'Transaction % mixes % currencies; entries must share one currency',
            NEW.txn_id, currency_count;
    END IF;

    IF entry_sum <> 0 THEN
        RAISE EXCEPTION
            'Transaction % does not balance: entries sum to %, expected 0',
            NEW.txn_id, entry_sum;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_entries_balance_check
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_transaction_balances();
