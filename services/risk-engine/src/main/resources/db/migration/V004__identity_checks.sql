CREATE TABLE identity_checks (
    id            UUID PRIMARY KEY,
    assessment_id VARCHAR(64)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    provider      VARCHAR(60)  NOT NULL,
    detail        VARCHAR(400),
    checked_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_identity_checks_assessment_id ON identity_checks (assessment_id);
