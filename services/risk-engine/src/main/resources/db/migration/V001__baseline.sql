-- V001 baseline — estrutura mínima da Risk Engine.
-- O modelo de dados completo (assessments, identity_checks, screening_results,
-- risk_scores, outbox) entra na Fase 1, cada tabela em sua própria migration.

-- Tabela de sanidade para validar a configuração do Flyway na Fase 0.
CREATE TABLE schema_bootstrap (
    id          SMALLINT PRIMARY KEY,
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO schema_bootstrap (id) VALUES (1);
