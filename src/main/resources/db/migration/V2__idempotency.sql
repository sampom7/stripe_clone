-- Idempotency keys.
--
-- The unique constraint on key is the whole mechanism. Two concurrent requests carrying
-- the same key both try to INSERT; exactly one wins, the other takes a unique-violation
-- and reads back what the winner stored. No check-then-set, no distributed lock, no cache
-- that can disagree with the database, because the record lives in the same database and
-- the same transaction as the work it protects.
--
-- request_hash exists so that reusing a key with a different body is caught rather than
-- silently answered with the wrong cached response. Stripe returns an error in that case
-- and so does this.

CREATE TABLE idempotency_keys (
    id              BIGSERIAL PRIMARY KEY,
    idempotency_key TEXT        NOT NULL UNIQUE,
    request_hash    TEXT        NOT NULL,
    endpoint        TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    resource_id     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,

    CONSTRAINT idempotency_status_valid CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),

    -- A completed record must carry the response it is going to replay.
    CONSTRAINT idempotency_completed_has_response CHECK (
        status <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL)
    )
);

CREATE INDEX idx_idempotency_created_at ON idempotency_keys (created_at);
CREATE INDEX idx_idempotency_status ON idempotency_keys (status);
