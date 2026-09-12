-- Transactional outbox.
--
-- Events get inserted here in the same transaction as the ledger entries they describe.
-- Either both land or neither does, so there's no way to publish an event for work that
-- got rolled back. A poller picks rows up afterwards and delivers them.
--
-- Delivery is at-least-once, not exactly-once. The poller can crash between sending and
-- marking the row published, in which case the event goes out twice. Consumers dedupe on
-- event_id. Anything claiming exactly-once over a network is either lying or doing
-- two-phase commit.

CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    event_id        TEXT        NOT NULL UNIQUE,
    event_type      TEXT        NOT NULL,
    aggregate_id    TEXT        NOT NULL,
    payload         TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER     NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,

    CONSTRAINT outbox_status_valid CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT outbox_published_has_timestamp CHECK (
        status <> 'PUBLISHED' OR published_at IS NOT NULL
    )
);

-- The poller's query: pending rows that are due, oldest first. Partial index because
-- published rows are the overwhelming majority over time and never get scanned.
CREATE INDEX idx_outbox_pending ON outbox (next_attempt_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_id);
CREATE INDEX idx_outbox_created_at ON outbox (created_at DESC);
