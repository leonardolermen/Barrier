-- Política de risco escrita pelo parceiro (P1 do Risk Control Plane).
--
-- Uma VERSÃO ativada é imutável: editar gera versão nova. Isso não é preciosismo de modelagem, é
-- o que torna a decisão replayável. O motor já registra `engine_version` em risk_scores, mas
-- regra de código de uma versão antiga some do binário — a decisão de 1.4.0 é irreproduzível por
-- isso. Política guardada como artefato imutável não tem esse problema: ela pode ser reexecutada
-- para sempre. É o segundo eixo de versão, e o que permitiu reabrir a recusa de "regra como dado".
--
-- `rules_json` guarda a árvore de predicados inteira, no padrão de partners_json / hits_json /
-- evaluated_json. Não precisa ser consultável por dentro: é lida inteira na avaliação.
CREATE TABLE risk_policies (
    id              UUID         PRIMARY KEY,
    tenant_id       VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    -- ONBOARDING hoje. Existe com um valor só para que o domínio transacional entre como valor
    -- novo, e não como migração de conceito.
    domain          VARCHAR(30)  NOT NULL,
    -- Inteiro monotônico POR TENANT (não por domínio), para que /v1/policies/{version} identifique
    -- uma versão sem carregar o domínio na rota.
    version         INTEGER      NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    -- Versão do catálogo de campos contra a qual a política foi escrita. Campo do catálogo pode ser
    -- marcado obsoleto, nunca removido — mesma razão de migration Flyway ser imutável: política
    -- antiga precisa continuar interpretável.
    catalog_version INTEGER      NOT NULL,
    rules_json      JSONB        NOT NULL,
    created_by      VARCHAR(120) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    activated_by    VARCHAR(120),
    activated_at    TIMESTAMPTZ,
    archived_at     TIMESTAMPTZ,
    CONSTRAINT uq_risk_policies_version UNIQUE (tenant_id, version)
);

-- Uma ACTIVE por (tenant, domínio). O índice parcial é o que impede duas políticas ativas
-- decidirem ao mesmo tempo, inclusive sob corrida entre réplicas.
CREATE UNIQUE INDEX uq_risk_policies_uma_ativa
    ON risk_policies (tenant_id, domain)
    WHERE status = 'ACTIVE';

-- "Qual a política ativa deste tenant" é a consulta do caminho quente: roda uma vez por avaliação.
CREATE INDEX idx_risk_policies_ativa
    ON risk_policies (tenant_id, domain, status);

-- Segundo eixo de versão da decisão, ao lado de engine_version. Nulo para tenant sem política.
ALTER TABLE risk_scores ADD COLUMN policy_version INTEGER;
