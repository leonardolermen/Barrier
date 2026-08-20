-- Chave de ordenacao da entrega.
--
-- Entregas com a mesma chave sao entregues em ordem; chaves diferentes correm em paralelo. A chave
-- e o SUBJECT: o parceiro nao pode receber "virou HIGH" antes de "virou MEDIUM" do mesmo cliente.
--
-- Nao e o tenant, de proposito: serializar por parceiro limitaria o cliente grande a uma entrega
-- por vez — justamente quem mais precisa de vazao. Nao e o assessment porque dois eventos sobre o
-- mesmo cliente (a decisao e a mudanca de nivel de risco) tem assessments diferentes e precisam ser
-- ordenados ENTRE SI.
--
-- NULL e permitido e significa "sem ordem exigida": evento cujo payload nao traz subject entra no
-- paralelismo sem restricao, em vez de bloquear ou ser bloqueado. Fail-open, mesmo principio da
-- V048 da risk-engine — o desconhecido nao pode travar a fila.
ALTER TABLE deliveries ADD COLUMN partition_key VARCHAR(64);

COMMENT ON COLUMN deliveries.partition_key IS
    'Chave de ordenacao (subjectId). Entregas com a mesma chave nunca correm em paralelo. '
    'NULL = sem ordem exigida.';

-- Sustenta o NOT EXISTS da reivindicacao, que pergunta "existe entrega desta chave em voo?".
-- Parcial: so linha nao-terminal bloqueia, e ao longo do tempo elas sao a minoria da tabela.
CREATE INDEX idx_deliveries_partition_key_em_voo
    ON deliveries (partition_key, claimed_at)
    WHERE status IN ('PENDING', 'FAILED') AND partition_key IS NOT NULL;
