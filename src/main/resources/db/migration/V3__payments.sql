-- Payment intents, charges and refunds.
--
-- These tables track the workflow. They don't hold money: every amount here has matching
-- ledger entries behind it, and the ledger is what's authoritative. If the two ever
-- disagree the ledger is right and this is a reporting bug.
--
-- Each intent gets its own hold account, created on authorize. That's what keeps
-- authorized funds out of the customer's available balance without a "held" column that
-- could fall out of step.

CREATE TABLE customers (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     TEXT        NOT NULL UNIQUE,
    email           TEXT,
    name            TEXT,
    account_id      TEXT        NOT NULL REFERENCES accounts (account_id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_customers_email ON customers (email);

CREATE TABLE payment_intents (
    id                  BIGSERIAL PRIMARY KEY,
    payment_intent_id   TEXT        NOT NULL UNIQUE,
    customer_id         TEXT        REFERENCES customers (customer_id),
    customer_account    TEXT        NOT NULL REFERENCES accounts (account_id),
    merchant_account    TEXT        NOT NULL REFERENCES accounts (account_id),
    hold_account        TEXT        REFERENCES accounts (account_id),
    amount              BIGINT      NOT NULL,
    amount_capturable   BIGINT      NOT NULL DEFAULT 0,
    amount_received     BIGINT      NOT NULL DEFAULT 0,
    currency            TEXT        NOT NULL,
    status              TEXT        NOT NULL,
    capture_method      TEXT        NOT NULL DEFAULT 'automatic',
    description         TEXT,
    cancellation_reason TEXT,
    last_error_code     TEXT,
    last_error_message  TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pi_amount_positive CHECK (amount > 0),
    CONSTRAINT pi_amounts_not_negative CHECK (
        amount_capturable >= 0 AND amount_received >= 0
    ),
    CONSTRAINT pi_received_within_amount CHECK (amount_received <= amount),
    CONSTRAINT pi_status_valid CHECK (status IN (
        'requires_payment_method',
        'requires_confirmation',
        'requires_capture',
        'processing',
        'succeeded',
        'canceled'
    )),
    CONSTRAINT pi_capture_method_valid CHECK (capture_method IN ('automatic', 'manual'))
);

CREATE INDEX idx_pi_customer ON payment_intents (customer_id);
CREATE INDEX idx_pi_status ON payment_intents (status);
CREATE INDEX idx_pi_created_at ON payment_intents (created_at DESC);

-- A charge is one successful capture. Partial captures on the same intent each make one.
CREATE TABLE charges (
    id                  BIGSERIAL PRIMARY KEY,
    charge_id           TEXT        NOT NULL UNIQUE,
    payment_intent_id   TEXT        NOT NULL REFERENCES payment_intents (payment_intent_id),
    amount              BIGINT      NOT NULL,
    amount_refunded     BIGINT      NOT NULL DEFAULT 0,
    currency            TEXT        NOT NULL,
    status              TEXT        NOT NULL,
    ledger_txn_id       TEXT        NOT NULL REFERENCES ledger_transactions (txn_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT charge_amount_positive CHECK (amount > 0),
    CONSTRAINT charge_refund_within_amount CHECK (
        amount_refunded >= 0 AND amount_refunded <= amount
    ),
    CONSTRAINT charge_status_valid CHECK (status IN ('succeeded', 'refunded', 'failed'))
);

CREATE INDEX idx_charge_intent ON charges (payment_intent_id);
CREATE INDEX idx_charge_created_at ON charges (created_at DESC);

CREATE TABLE refunds (
    id              BIGSERIAL PRIMARY KEY,
    refund_id       TEXT        NOT NULL UNIQUE,
    charge_id       TEXT        NOT NULL REFERENCES charges (charge_id),
    amount          BIGINT      NOT NULL,
    currency        TEXT        NOT NULL,
    reason          TEXT,
    status          TEXT        NOT NULL,
    ledger_txn_id   TEXT        NOT NULL REFERENCES ledger_transactions (txn_id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT refund_amount_positive CHECK (amount > 0),
    CONSTRAINT refund_status_valid CHECK (status IN ('succeeded', 'failed', 'canceled')),
    CONSTRAINT refund_reason_valid CHECK (reason IS NULL OR reason IN (
        'duplicate', 'fraudulent', 'requested_by_customer'
    ))
);

CREATE INDEX idx_refund_charge ON refunds (charge_id);
