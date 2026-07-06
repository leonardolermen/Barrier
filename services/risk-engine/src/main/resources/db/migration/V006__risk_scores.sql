CREATE TABLE risk_scores (
    id             UUID PRIMARY KEY,
    assessment_id  VARCHAR(64)   NOT NULL,
    level          VARCHAR(10)   NOT NULL,
    score          INTEGER       NOT NULL,
    recommendation VARCHAR(10)   NOT NULL,
    results_json   VARCHAR(4000) NOT NULL,
    engine_version VARCHAR(40)   NOT NULL,
    scored_at      TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_risk_scores_assessment_id ON risk_scores (assessment_id);
