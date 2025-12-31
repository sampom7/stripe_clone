-- Create accounts table
CREATE TABLE IF NOT EXISTS accounts (
    id BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(50) UNIQUE NOT NULL,
    account_type VARCHAR(50) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance NUMERIC(19, 4) NOT NULL DEFAULT 0,
    available_balance NUMERIC(19, 4) NOT NULL DEFAULT 0,
    hold_balance NUMERIC(19, 4) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_account_external_id ON accounts(external_id);
CREATE INDEX idx_account_type ON accounts(account_type);

-- Create ledger_transactions table
CREATE TABLE IF NOT EXISTS ledger_transactions (
    id BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(50) UNIQUE NOT NULL,
    transaction_type VARCHAR(50) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    description VARCHAR(500),
    metadata TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_ledger_txn_external_id ON ledger_transactions(external_id);
CREATE INDEX idx_ledger_txn_type ON ledger_transactions(transaction_type);
CREATE INDEX idx_ledger_txn_status ON ledger_transactions(status);

-- Create ledger_entries table
CREATE TABLE IF NOT EXISTS ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES ledger_transactions(id),
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    entry_type VARCHAR(20) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance_after NUMERIC(19, 4) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_entry_transaction ON ledger_entries(transaction_id);
CREATE INDEX idx_entry_account ON ledger_entries(account_id);
CREATE INDEX idx_entry_type ON ledger_entries(entry_type);

-- Create payments table
CREATE TABLE IF NOT EXISTS payments (
    id BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(50) UNIQUE NOT NULL,
    idempotency_key VARCHAR(100) UNIQUE NOT NULL,
    customer_id VARCHAR(50) NOT NULL,
    merchant_id VARCHAR(50) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    payment_method VARCHAR(50),
    description VARCHAR(500),
    metadata TEXT,
    failure_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_payment_external_id ON payments(external_id);
CREATE INDEX idx_payment_customer ON payments(customer_id);
CREATE INDEX idx_payment_status ON payments(status);
CREATE INDEX idx_payment_idempotency ON payments(idempotency_key);

-- Create authorizations table
CREATE TABLE IF NOT EXISTS authorizations (
    id BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(50) UNIQUE NOT NULL,
    payment_id BIGINT NOT NULL REFERENCES payments(id),
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    authorized_at TIMESTAMP,
    expires_at TIMESTAMP,
    failure_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_auth_external_id ON authorizations(external_id);
CREATE INDEX idx_auth_payment ON authorizations(payment_id);
CREATE INDEX idx_auth_status ON authorizations(status);

-- Create captures table
CREATE TABLE IF NOT EXISTS captures (
    id BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(50) UNIQUE NOT NULL,
    payment_id BIGINT NOT NULL REFERENCES payments(id),
    authorization_id BIGINT NOT NULL REFERENCES authorizations(id),
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    captured_at TIMESTAMP,
    failure_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_capture_external_id ON captures(external_id);
CREATE INDEX idx_capture_payment ON captures(payment_id);
CREATE INDEX idx_capture_authorization ON captures(authorization_id);
CREATE INDEX idx_capture_status ON captures(status);

-- Create settlements table
CREATE TABLE IF NOT EXISTS settlements (
    id BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(50) UNIQUE NOT NULL,
    capture_id BIGINT NOT NULL REFERENCES captures(id),
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    settlement_date TIMESTAMP,
    failure_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_settlement_external_id ON settlements(external_id);
CREATE INDEX idx_settlement_capture ON settlements(capture_id);
CREATE INDEX idx_settlement_status ON settlements(status);
CREATE INDEX idx_settlement_date ON settlements(settlement_date);

