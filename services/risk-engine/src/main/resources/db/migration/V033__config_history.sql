-- Histórico de configuração: o que valia quando a decisão foi tomada.
--
-- `tenant_risk_config` e `risk_rule_registry` eram sobrescritos no lugar. Uma decisão de março
-- gravava a versão do motor (ENGINE_VERSION), as regras avaliadas e a versão das listas — tudo
-- menos com que configuração. Passados seis meses, "por que este cliente foi aprovado?" respondia
-- com o parâmetro de hoje, que pode nunca ter existido em março. Pior no caso de kill switch: uma
-- regra desligada por uma semana e religada não deixava nenhum vestígio da semana em que não rodou.
--
-- Duas metades resolvem isso, e são complementares:
--   1. o parâmetro efetivo de cada regra vai junto da decisão (`evaluated_json`), inclusive das
--      que passaram — resolve "com que valor esta avaliação rodou";
--   2. estas tabelas guardam a linha do tempo da configuração — resolvem "quem mudou, quando e do
--      quê para quê", que a decisão sozinha não conta.
--
-- Append-only por convenção: nada no código atualiza ou apaga estas linhas. Sem trigger de banco
-- de propósito — o histórico é escrito pelo mesmo serviço que faz a alteração, na mesma transação,
-- e uma trigger esconderia essa escrita de quem lê o serviço.

-- `updated_by` faltava no registry, que `tenant_risk_config` já tinha: ligar e desligar regra de
-- risco é a operação mais sensível do sistema e era a única sem autoria.
ALTER TABLE risk_rule_registry ADD COLUMN updated_by VARCHAR(120);

CREATE TABLE risk_rule_registry_history (
    id            UUID PRIMARY KEY,
    rule_code     VARCHAR(60)  NOT NULL,
    -- estado NOVO da linha; a versão anterior é a penúltima entrada do histórico
    enabled       BOOLEAN      NOT NULL,
    criticality   VARCHAR(20)  NOT NULL,
    description   VARCHAR(500) NOT NULL,
    valid_from    TIMESTAMPTZ,
    valid_until   TIMESTAMPTZ,
    updated_by    VARCHAR(120),
    changed_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_rule_registry_history_code ON risk_rule_registry_history (rule_code, changed_at DESC);

CREATE TABLE tenant_risk_config_history (
    id          UUID         PRIMARY KEY,
    tenant_id   VARCHAR(40)  NOT NULL,
    rule_code   VARCHAR(60)  NOT NULL,
    param_key   VARCHAR(60)  NOT NULL,
    -- NULL = parâmetro removido (voltou ao default global). Sem esta distinção, "não existe linha"
    -- e "foi apagado" ficariam iguais, e a volta ao default é exatamente uma mudança de controle.
    param_value VARCHAR(4000),
    updated_by  VARCHAR(120) NOT NULL,
    changed_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_tenant_risk_config_history_key
    ON tenant_risk_config_history (tenant_id, rule_code, param_key, changed_at DESC);
