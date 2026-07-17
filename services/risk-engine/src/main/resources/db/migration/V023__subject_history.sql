-- Histórico interno do subject (chargeback, PIX devolvido, denúncia, conta encerrada por
-- fraude) — alimenta HistoryRiskRule. Eventos são registrados via API interna/admin (não há
-- ainda um pipeline automático de transação/PIX; ver Fase 8 item 7 no risk-engine-plan.md).
CREATE TABLE subject_history (
    id          UUID PRIMARY KEY,
    subject_id  UUID         NOT NULL REFERENCES subjects (id),
    event_type  VARCHAR(40)  NOT NULL,
    detail      VARCHAR(500),
    occurred_at TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_subject_history_subject ON subject_history (subject_id);
