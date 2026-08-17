-- Trilha das decisões de reavaliação (ADR-0019).
--
-- O buraco que esta tabela fecha: um rescreening que NÃO gerou avaliação era indistinguível de um
-- que nunca rodou. O auditor via ausência de avaliação nos dois casos e não tinha como separar "o
-- controle rodou e concluiu que não havia o que reavaliar" de "o controle estava desligado" — que é
-- exatamente a distinção que o motor de risco faz questão de registrar em toda regra (rodou e
-- passou ≠ estava desligada).
--
-- Grava também o "não": a linha que diz "não reavaliei porque faltavam 300 dias para o intervalo
-- mínimo daquele nível de risco" é a que se pede numa fiscalização.
CREATE TABLE reassessment_decisions (
    id            UUID         PRIMARY KEY,
    subject_id    UUID         NOT NULL REFERENCES subjects (id),
    tenant_id     VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    trigger_type  VARCHAR(30)  NOT NULL,
    -- Fonte/versão da lista, referência da verificação de assurance, operador que pediu.
    trigger_detail VARCHAR(200),
    reassessed   BOOLEAN      NOT NULL,
    -- Por que NÃO reavaliou: intervalo_minimo | sem_alteracao_material | politica_desligada.
    -- Nulo quando reavaliou.
    reason        VARCHAR(60),
    -- Nível corrente no momento da decisão: é dele que sai o intervalo mínimo aplicado. Guardado
    -- junto porque a projeção muda depois, e a trilha precisa dizer qual intervalo valia AGORA.
    risk_level    VARCHAR(10),
    assessment_id UUID         REFERENCES assessments (id),
    decided_at    TIMESTAMPTZ  NOT NULL
);

-- "O que aconteceu com este cliente" é a consulta da fiscalização.
CREATE INDEX idx_reassessment_decisions_subject
    ON reassessment_decisions (subject_id, tenant_id, decided_at DESC);
