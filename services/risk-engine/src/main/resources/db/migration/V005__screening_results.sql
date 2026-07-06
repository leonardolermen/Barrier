CREATE TABLE screening_results (
    id            UUID PRIMARY KEY,
    assessment_id VARCHAR(64)   NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    hits_json     VARCHAR(4000) NOT NULL,
    checked_at    TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_screening_results_assessment_id ON screening_results (assessment_id);
