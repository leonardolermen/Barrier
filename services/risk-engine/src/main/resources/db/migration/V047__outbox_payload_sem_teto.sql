-- Payload da outbox deixa de ter teto.
--
-- A V026 tirou o VARCHAR(4000) das colunas de EVIDÊNCIA (hits_json, results_json, partners_json)
-- depois de um CNPJ com 41 sócios derrubar a avaliação inteira. O mesmo teto ficou no
-- `outbox.payload`, e ali o modo de falha é pior: a gravação da outbox roda na MESMA transação
-- que conclui a avaliação, então um payload grande não perde o evento — reverte a conclusão.
-- A avaliação volta para a fila, refaz as consultas pagas de bureau e falha de novo, até
-- FALHA_PROCESSAMENTO.
--
-- Hoje nenhum dos três eventos chega perto de 4000 (todos têm forma fixa e campos curtos), então
-- isto é preventivo, não corretivo. O que o torna necessário é que nada impede o próximo campo:
-- basta alguém acrescentar os fatores explicáveis ou o QSA ao payload — informação que o parceiro
-- legitimamente quer — para o teto voltar a morder, e a falha aparecer como avaliação travada,
-- não como "coluna pequena".
--
-- TEXT e não JSONB, ao contrário da V026. JSONB normaliza o documento (reordena chaves, descarta
-- espaços), e o payload aqui é a string que a Webhook API lê de volta para ASSINAR com HMAC e
-- enviar ao parceiro. Preservar os bytes exatos que o produtor serializou é requisito; a
-- consultabilidade que justificou JSONB na evidência não vale o risco de mexer no que é assinado.
ALTER TABLE outbox
    ALTER COLUMN payload TYPE TEXT;

COMMENT ON COLUMN outbox.payload IS
    'JSON do evento, como string. TEXT (não JSONB) de propósito: estes bytes viram o corpo '
    'assinado por HMAC na entrega ao parceiro, e a normalização do JSONB os alteraria.';
