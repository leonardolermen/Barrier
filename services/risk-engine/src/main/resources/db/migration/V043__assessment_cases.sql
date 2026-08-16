-- Mesa de análise: caso, fila nomeada e ações manuais (fila-origem F7).
--
-- Até aqui a revisão manual era um POST /decision: sem fila, sem atribuição, sem histórico e sem
-- SLA — reconhecido no plano de remediação como "um POST /decision não é case management". O
-- analista via um monte de avaliação em EM_REVISAO e nada dizia qual era dele, há quanto tempo
-- esperava, nem o que já tinha sido tentado.
--
-- Duas tabelas em vez de colunas em `assessments`: o caso é o ciclo de vida OPERACIONAL (quem pegou,
-- em que fila está, quanto tempo consumiu), enquanto `assessments` é a decisão de risco. Misturá-los
-- faria o módulo da mesa escrever na tabela do motor, e a fronteira entre "o que o motor decidiu" e
-- "o que a operação fez" é justamente a que precisa ficar nítida numa fiscalização.
CREATE TABLE assessment_cases (
    assessment_id UUID         PRIMARY KEY REFERENCES assessments (id),
    tenant_id     VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    -- analise_padrao | alcada_risco | aguardando_parceiro
    queue         VARCHAR(30)  NOT NULL,
    assigned_to   VARCHAR(120),
    opened_at     TIMESTAMPTZ  NOT NULL,
    closed_at     TIMESTAMPTZ
);

-- A fila do analista: "o que está aberto nesta fila, mais antigo primeiro".
CREATE INDEX idx_assessment_cases_queue
    ON assessment_cases (tenant_id, queue, opened_at)
    WHERE closed_at IS NULL;

-- Ações manuais como EVENTOS, não só o desfecho final.
--
-- É delas que o SLA pausável é reconstruído: sem o par pedido/recebimento de documento não há como
-- provar que a espera foi do parceiro, e o SLA passaria a medir a lentidão dele culpando a mesa.
-- Guardar só a decisão final destruiria essa informação — o desfecho não diz quanto tempo o caso
-- passou esperando alguém de fora.
CREATE TABLE assessment_actions (
    id            UUID         PRIMARY KEY,
    assessment_id UUID         NOT NULL REFERENCES assessments (id),
    tenant_id     VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    -- ASSIGNED | MOVED | DOCUMENT_REQUESTED | DOCUMENT_RECEIVED | NOTE | DECIDED
    action_type   VARCHAR(30)  NOT NULL,
    -- Quem fez. Texto autodeclarado, mesmo padrão de assessments.reviewed_by.
    actor         VARCHAR(120) NOT NULL,
    detail        VARCHAR(500),
    occurred_at   TIMESTAMPTZ  NOT NULL
);

-- Reconstruir a linha do tempo de um caso é a consulta quente: SLA, auditoria e a tela do analista.
CREATE INDEX idx_assessment_actions_case
    ON assessment_actions (assessment_id, occurred_at);
