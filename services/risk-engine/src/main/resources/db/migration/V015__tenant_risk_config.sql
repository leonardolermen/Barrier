-- Override por tenant de parâmetros de regras de risco configuráveis (ex.: NEW_COMPANY,
-- SENSITIVE_CNAE). Chave composta em vez de blob JSON: permite override parcial (o tenant
-- configura só um parâmetro e herda o resto do default global) e fica auditável linha a linha.
-- Regras regulatórias fixas (bandas de score, PEP, sanção, identidade) não têm rule_code aqui —
-- a allowlist é aplicada na camada de aplicação (API de gestão).
CREATE TABLE tenant_risk_config (
    id            UUID PRIMARY KEY,
    tenant_id     VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    rule_code     VARCHAR(60)  NOT NULL,
    param_key     VARCHAR(60)  NOT NULL,
    param_value   VARCHAR(4000) NOT NULL,
    updated_by    VARCHAR(120) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_risk_config UNIQUE (tenant_id, rule_code, param_key)
);

CREATE INDEX idx_tenant_risk_config_tenant ON tenant_risk_config (tenant_id);
