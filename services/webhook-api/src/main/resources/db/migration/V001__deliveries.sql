CREATE TABLE deliveries (
    id              UUID PRIMARY KEY,
    event_id        UUID          NOT NULL UNIQUE,
    assessment_id   VARCHAR(64)   NOT NULL,
    target_url      VARCHAR(500)  NOT NULL,
    payload         VARCHAR(4000) NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    attempts        INTEGER       NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),
    next_attempt_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL,
    delivered_at    TIMESTAMPTZ
);

CREATE INDEX idx_deliveries_status_next_attempt ON deliveries (status, next_attempt_at);
