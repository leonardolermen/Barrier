-- Posse (lease) de um evento da outbox, no mesmo padrão que a V023 aplicou às avaliações.
--
-- O relay era `@Transactional` e publicava no Kafka com `.join()` DENTRO da transação, segurando
-- `FOR UPDATE SKIP LOCKED` em até 100 linhas enquanto esperava o broker. É o anti-padrão que o
-- Javadoc do AssessmentProcessor descreve como causa de incidente ("uma conexão do pool ficava
-- presa por minutos") e que foi corrigido lá — e não aqui. Com o broker em rebalance, uma conexão
-- do pool e 100 linhas ficavam travadas até o timeout.
--
-- O lock do banco era o que impedia duas réplicas de publicarem o mesmo evento, então tirar a
-- publicação da transação exige substituí-lo por posse explícita: `claimed_at` com expiração.
-- Se a instância morrer no meio da publicação, a linha volta a ser reivindicável sozinha — uma
-- flag booleana exigiria um processo de faxina para destravá-la.
ALTER TABLE outbox ADD COLUMN claimed_at TIMESTAMPTZ;

-- Substitui o índice anterior: a varredura agora filtra também por posse.
DROP INDEX IF EXISTS idx_outbox_status_occurred_at;

CREATE INDEX idx_outbox_claimable ON outbox (status, claimed_at, occurred_at);
