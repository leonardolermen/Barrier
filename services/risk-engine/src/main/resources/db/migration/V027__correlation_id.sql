-- Correlação da requisição, persistida para atravessar a fronteira assíncrona.
--
-- A decisão acontece num @Scheduled, noutra thread, minutos depois do POST do cliente — e o MDC só
-- existe na thread do servlet. O resultado é que os logs da decisão não tinham nem correlationId
-- nem assessmentId: investigar uma aprovação indevida era grep em log de texto por documento
-- mascarado, torcendo para achar o assessment certo.
--
-- Guardar na linha (e não só no log) é o que permite RESTAURAR a correlação no processamento e
-- propagá-la adiante, pela outbox, até a entrega do webhook. Com isso um único id liga
-- POST → decisão → evento → callback.
ALTER TABLE assessments ADD COLUMN correlation_id VARCHAR(64);

ALTER TABLE outbox ADD COLUMN correlation_id VARCHAR(64);

-- Busca por correlação é o caminho de investigação; sem índice seria varredura da tabela toda.
CREATE INDEX idx_assessments_correlation ON assessments (correlation_id);
