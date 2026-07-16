-- Registry global de regras de risco: permite habilitar/desabilitar uma regra inteira e
-- definir vigência sem deploy (ajuste operacional/compliance), além de guardar a criticidade
-- (informativa, para transparência de auditoria) de cada família de regra. Diferente de
-- tenant_risk_config (que ajusta PARÂMETROS por parceiro) — isto é um kill switch/vigência
-- global, aplicado a todos os tenants.
CREATE TABLE risk_rule_registry (
    rule_code    VARCHAR(60) PRIMARY KEY,
    description  VARCHAR(500) NOT NULL,
    criticality  VARCHAR(20) NOT NULL,
    enabled      BOOLEAN     NOT NULL DEFAULT true,
    valid_from   TIMESTAMPTZ,
    valid_until  TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO risk_rule_registry (rule_code, description, criticality) VALUES
    ('IDENTITY', 'Verificação de identidade (bureau)', 'BLOCK'),
    ('SANCTION', 'Apontamento em lista de sanções (OFAC/ONU/CGU)', 'BLOCK'),
    ('PEP', 'Pessoa Exposta Politicamente — EDD', 'REVIEW'),
    ('NEW_COMPANY', 'Empresa recém-aberta', 'ALERT'),
    ('SENSITIVE_CNAE', 'CNAE sensível a PLD-FT', 'ALERT'),
    ('CORPORATE_STRUCTURE', 'Quadro societário (KYB de 1º grau)', 'ALERT');
