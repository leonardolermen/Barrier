CREATE TABLE assessments (
    id             UUID PRIMARY KEY,
    document_type  VARCHAR(10)  NOT NULL,
    document_value VARCHAR(20)  NOT NULL,
    name           VARCHAR(200) NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    risk_level     VARCHAR(10),
    decision       VARCHAR(200),
    created_at     TIMESTAMPTZ  NOT NULL,
    completed_at   TIMESTAMPTZ
);

CREATE INDEX idx_assessments_status_created_at ON assessments (status, created_at);
