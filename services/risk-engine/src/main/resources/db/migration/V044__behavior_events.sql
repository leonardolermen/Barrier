-- Ingestão de evento comportamental (fila-origem F8).
--
-- O motor decide no onboarding e reage a mudança de lista (rescreening) e a verificação de
-- identidade (assurance). O que nunca existiu foi reagir ao COMPORTAMENTO pós-onboarding —
-- transação atípica, troca de dispositivo, tentativa de acesso — que é o insumo do monitoramento
-- contínuo da Circular 3.978 além do screening de listas.
--
-- Esta tabela é o acervo de FATOS IMUTÁVEIS. Não há UPDATE: um evento comportamental descreve algo
-- que aconteceu, e corrigir o passado seria destruir a base sobre a qual uma decisão foi tomada.
-- Correção se faz com evento novo, nunca reescrevendo o antigo.
--
-- `payload` é JSONB e deliberadamente sem esquema fixo: o que cada parceiro considera relevante
-- varia (valor e canal para um banco, item e frete para um marketplace), e engessar isso agora
-- fecharia a porta antes de saber o que as regras vão querer ler. O preço é conhecido — consulta
-- sobre payload depende do parceiro —, e o catálogo de eventos é onde a forma fica documentada.
CREATE TABLE behavior_events (
    id             UUID         PRIMARY KEY,
    tenant_id      VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    subject_id     UUID         NOT NULL REFERENCES subjects (id),
    -- Tipo livre acordado com o parceiro (transaction, login, device_change...). O barramento não
    -- precisa saber o que a entidade é para transportar o fato — mesma escolha do `document`
    -- genérico do tzofe.
    event_type     VARCHAR(60)  NOT NULL,
    -- Quando aconteceu no mundo (informado pelo parceiro) x quando chegou aqui. Os dois importam:
    -- a diferença entre eles é a latência da integração, e sem `received_at` um parceiro que
    -- reenvia histórico antigo seria indistinguível de um que está atrasado.
    occurred_at    TIMESTAMPTZ  NOT NULL,
    received_at    TIMESTAMPTZ  NOT NULL,
    payload        JSONB,
    -- Idempotência: o id do evento no sistema do parceiro. Ingestão comportamental é at-least-once
    -- por natureza (retry de app, reprocessamento de fila do parceiro), e contar a mesma transação
    -- duas vezes falsearia qualquer sinal construído por contagem.
    source_event_id VARCHAR(120) NOT NULL,
    CONSTRAINT uq_behavior_events_source UNIQUE (tenant_id, source_event_id)
);

-- "O que este cliente fez recentemente" é a consulta que toda regra comportamental vai fazer.
CREATE INDEX idx_behavior_events_subject
    ON behavior_events (subject_id, tenant_id, occurred_at DESC);
