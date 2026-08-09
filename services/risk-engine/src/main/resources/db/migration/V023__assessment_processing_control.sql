-- Controle de processamento das avaliações.
--
-- Três problemas resolvidos por estas colunas:
--
-- 1. DUPLICAÇÃO: o poller lia as pendentes sem lock, então duas réplicas processavam a mesma
--    avaliação, chamavam o bureau duas vezes e emitiam dois eventos com decisões possivelmente
--    divergentes — ambos entregues ao cliente, sem critério de desempate. `claimed_at` é a posse
--    (lease) da linha: quem reivindica, processa.
--
-- 2. POISON PILL: uma exceção fazia a avaliação voltar ao topo da fila a cada 2s, para sempre,
--    sem limite e sem estado de erro. `attempts`/`last_error`/`next_attempt_at` dão backoff e
--    desistência, e o status FALHA_PROCESSAMENTO tira a avaliação do limbo — o cliente passa a
--    saber que falhou em vez de vê-la eternamente EM_ANALISE.
--
-- 3. LOST UPDATE: sem `version`, dois writers concorrentes sobrescreviam a decisão um do outro
--    silenciosamente.
--
-- `claimed_at` funciona como lease com expiração, e não como flag booleana, de propósito: se a
-- instância morrer no meio do processamento, a linha volta a ser reivindicável sozinha depois do
-- prazo. Uma flag exigiria um processo separado de faxina para destravá-la.
ALTER TABLE assessments ADD COLUMN claimed_at      TIMESTAMPTZ;
ALTER TABLE assessments ADD COLUMN attempts        INTEGER      NOT NULL DEFAULT 0;
ALTER TABLE assessments ADD COLUMN last_error      VARCHAR(500);
ALTER TABLE assessments ADD COLUMN next_attempt_at TIMESTAMPTZ;
ALTER TABLE assessments ADD COLUMN version         BIGINT       NOT NULL DEFAULT 0;

-- Suporta a varredura de reivindicáveis sem varrer a tabela inteira.
CREATE INDEX idx_assessments_claimable
    ON assessments (status, next_attempt_at, claimed_at, created_at);
