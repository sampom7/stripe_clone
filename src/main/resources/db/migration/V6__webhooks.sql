-- Webhook endpoints and delivery tracking.
--
-- Built on the outbox from V4. An outbox row is "this happened"; a webhook_deliveries row
-- is "we still owe this endpoint a copy of it". Fanning out one event to several endpoints
-- means several delivery rows, each retried on its own schedule, so one endpoint being
-- down doesn't hold up anyone else's.

CREATE TABLE webhook_endpoints (
    id              BIGSERIAL PRIMARY KEY,
    endpoint_id     TEXT        NOT NULL UNIQUE,
    url             TEXT        NOT NULL,
    secret          TEXT        NOT NULL,
    description     TEXT,
    enabled_events  TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'enabled',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT webhook_status_valid CHECK (status IN ('enabled', 'disabled')),
    CONSTRAINT webhook_url_is_http CHECK (url ~ '^https?://')
);

CREATE INDEX idx_webhook_status ON webhook_endpoints (status);

CREATE TABLE webhook_deliveries (
    id              BIGSERIAL PRIMARY KEY,
    delivery_id     TEXT        NOT NULL UNIQUE,
    endpoint_id     TEXT        NOT NULL REFERENCES webhook_endpoints (endpoint_id),
    event_id        TEXT        NOT NULL,
    event_type      TEXT        NOT NULL,
    payload         TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER     NOT NULL DEFAULT 0,
    response_status INTEGER,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMPTZ,

    CONSTRAINT delivery_status_valid CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),

    -- One copy per endpoint per event. Makes the fan-out itself idempotent: replaying an
    -- outbox event can't queue a second delivery to the same endpoint.
    CONSTRAINT delivery_unique_per_endpoint UNIQUE (endpoint_id, event_id)
);

CREATE INDEX idx_delivery_pending ON webhook_deliveries (next_attempt_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_delivery_event ON webhook_deliveries (event_id);
CREATE INDEX idx_delivery_endpoint ON webhook_deliveries (endpoint_id);
