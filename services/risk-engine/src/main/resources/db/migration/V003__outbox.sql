CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    aggregate_id VARCHAR(64)   NOT NULL,
    type         VARCHAR(120)  NOT NULL,
    payload      VARCHAR(4000) NOT NULL,
    version      INTEGER       NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    attempts     INTEGER       NOT NULL DEFAULT 0,
    occurred_at  TIMESTAMPTZ   NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    sent_at      TIMESTAMPTZ
);

CREATE INDEX idx_outbox_status_occurred_at ON outbox (status, occurred_at);
