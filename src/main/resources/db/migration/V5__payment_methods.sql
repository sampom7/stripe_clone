-- Payment methods.
--
-- No real card data is ever stored here, and there's no encryption, because there's
-- nothing sensitive to protect: the "card numbers" this system accepts are Stripe's
-- published test numbers and nothing else. A real processor would tokenise at the edge
-- and never let a PAN reach its own database. Storing last4 and brand is what's left
-- after tokenisation, which is what this mirrors.

CREATE TABLE payment_methods (
    id                  BIGSERIAL PRIMARY KEY,
    payment_method_id   TEXT        NOT NULL UNIQUE,
    customer_id         TEXT        REFERENCES customers (customer_id),
    type                TEXT        NOT NULL,
    brand               TEXT,
    last4               TEXT,
    exp_month           INTEGER,
    exp_year            INTEGER,
    fingerprint         TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pm_type_valid CHECK (type IN ('card')),
    CONSTRAINT pm_last4_shape CHECK (last4 IS NULL OR last4 ~ '^[0-9]{4}$'),
    CONSTRAINT pm_exp_month_valid CHECK (
        exp_month IS NULL OR (exp_month >= 1 AND exp_month <= 12)
    )
);

CREATE INDEX idx_pm_customer ON payment_methods (customer_id);
CREATE INDEX idx_pm_fingerprint ON payment_methods (fingerprint);

-- Which payment method an intent is going to use.
ALTER TABLE payment_intents
    ADD COLUMN payment_method_id TEXT REFERENCES payment_methods (payment_method_id);

CREATE INDEX idx_pi_payment_method ON payment_intents (payment_method_id);
