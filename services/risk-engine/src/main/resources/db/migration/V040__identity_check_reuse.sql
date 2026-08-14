-- V040: identity_checks passa a ser pesquisável por (tenant, documento, nome) para permitir
-- reuso de uma verificação recente em vez de pagar a mesma consulta de bureau outra vez.
--
-- reused_from_id é o que impede a trilha de mentir: um check reaproveitado tem checked_at de
-- agora (é quando esta avaliação decidiu) e aponta para a consulta que de fato foi à rede.
-- Sem essa coluna, evidência reaproveitada e evidência fresca ficam indistinguíveis.

ALTER TABLE identity_checks ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE identity_checks ADD COLUMN document_type VARCHAR(10);
ALTER TABLE identity_checks ADD COLUMN document_digits VARCHAR(14);
ALTER TABLE identity_checks ADD COLUMN name VARCHAR(200);
ALTER TABLE identity_checks ADD COLUMN reused_from_id UUID REFERENCES identity_checks (id);

-- Parcial: só linhas com documento preenchido servem de origem de reuso, e as linhas
-- históricas (anteriores a esta migration) nunca terão. O índice não paga por elas.
CREATE INDEX idx_identity_checks_reuse
  ON identity_checks (tenant_id, document_type, document_digits, checked_at DESC)
  WHERE document_digits IS NOT NULL AND reused_from_id IS NULL;

COMMENT ON COLUMN identity_checks.reused_from_id IS
  'Quando preenchido, este check copiou o desfecho da consulta apontada em vez de ir ao bureau.';
