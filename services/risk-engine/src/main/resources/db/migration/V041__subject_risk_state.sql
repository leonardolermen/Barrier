-- Risco corrente do cliente, por tenant.
--
-- `risk_scores` guarda uma linha por avaliação e nunca é sobrescrito — é a trilha, e continua
-- sendo. O que não existia era a resposta para "qual é o risco deste cliente agora": era preciso
-- caçar a última avaliação concluída dele, e nada no código fazia isso. Sem esta projeção não há
-- como responder "meus clientes em CRITICAL", avisar o parceiro quando o risco muda, nem dizer ao
-- rescreening o que mudou em relação a antes.
--
-- A chave é (subject_id, tenant_id) e NÃO subject_id: a decisão de aceitar/recusar é por tenant no
-- assessment (ADR-0011/ADR-0012), então o mesmo cliente pode estar APROVADO num parceiro e
-- REPROVADO em outro. Uma projeção global teria que escolher um dos dois, e escolheria errado para
-- o outro — além de vazar risco entre parceiros, que é exatamente o que a V024 corrigiu no
-- cadastro.
CREATE TABLE subject_risk_state (
    subject_id     UUID         NOT NULL REFERENCES subjects (id),
    tenant_id      VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    risk_level     VARCHAR(10)  NOT NULL,
    risk_score     INTEGER      NOT NULL,
    decision       VARCHAR(30)  NOT NULL,
    assessment_id  UUID         NOT NULL REFERENCES assessments (id),
    engine_version VARCHAR(40),
    -- Quando a avaliação que produziu este estado concluiu. É por este campo, e não pela ordem de
    -- gravação, que o upsert decide se sobrescreve: avaliações concorrentes (rescreening,
    -- assurance, decisão manual) concluem fora de ordem, e uma avaliação iniciada antes e
    -- concluída depois não pode enterrar um estado mais novo.
    evaluated_at   TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (subject_id, tenant_id)
);

-- Responde "meus clientes em CRITICAL" sem varrer a tabela.
CREATE INDEX idx_subject_risk_state_level ON subject_risk_state (tenant_id, risk_level);

-- Backfill: a última avaliação concluída de cada (subject, tenant) é exatamente o que a projeção
-- teria gravado se existisse desde o início. Sem isto, todo cliente já avaliado apareceria como
-- "sem risco corrente" até ser reavaliado — e o fallback do GET esconderia o buraco em vez de
-- fechá-lo.
--
-- `risk_scores.assessment_id` é VARCHAR (V006) e `assessments.id` é UUID: o cast é necessário.
-- O LEFT JOIN é deliberado — avaliação concluída sem linha de score existe (SOLICITAR_DOCUMENTO
-- por cadastro incompleto, por exemplo), e perdê-la no backfill deixaria justamente os clientes
-- pendentes de fora da projeção.
INSERT INTO subject_risk_state (
    subject_id, tenant_id, risk_level, risk_score, decision,
    assessment_id, engine_version, evaluated_at, updated_at)
SELECT DISTINCT ON (a.subject_id, a.tenant_id)
       a.subject_id,
       a.tenant_id,
       a.risk_level,
       COALESCE(rs.score, 0),
       a.status,
       a.id,
       rs.engine_version,
       a.completed_at,
       now()
  FROM assessments a
  LEFT JOIN risk_scores rs ON rs.assessment_id = a.id::text
 WHERE a.completed_at IS NOT NULL
   AND a.risk_level  IS NOT NULL
   AND a.subject_id  IS NOT NULL
 ORDER BY a.subject_id, a.tenant_id, a.completed_at DESC;
