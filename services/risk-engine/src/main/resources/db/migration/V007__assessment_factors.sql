-- Fatores explicáveis da decisão, guardados junto da avaliação (uma linha por fator,
-- separados por quebra de linha) para retorno no GET sem consultar outro contexto.
ALTER TABLE assessments ADD COLUMN factors VARCHAR(2000);
