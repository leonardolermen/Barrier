-- Payload da entrega deixa de ter teto. Simétrico da V047 da Risk Engine.
--
-- `deliveries.payload` guarda o corpo que vai para o endpoint do parceiro. Com VARCHAR(4000), um
-- evento maior que o teto falharia ao gravar a entrega — e o consumo é at-least-once com retry,
-- então o efeito seria a mensagem voltar, falhar de novo e terminar na DLT: uma decisão de KYC
-- que o parceiro nunca recebe, por limite de coluna.
--
-- TEXT e não JSONB, e aqui o motivo é mais forte que na Risk Engine: `WebhookDeliveryService`
-- calcula o HMAC sobre `delivery.payload()` LIDO DO BANCO e envia essa mesma string como corpo.
-- JSONB normaliza (reordena chaves, remove espaços), então o parceiro receberia bytes diferentes
-- dos que o produtor serializou — e qualquer verificação dele que compare com o evento original,
-- ou que dependa da ordem das chaves, passaria a divergir. Fidelidade de bytes é requisito nesta
-- coluna, não preferência.
ALTER TABLE deliveries
    ALTER COLUMN payload TYPE TEXT;

COMMENT ON COLUMN deliveries.payload IS
    'Corpo exato entregue ao parceiro e assinado por HMAC. TEXT (não JSONB): a normalização do '
    'JSONB alteraria os bytes assinados.';
