-- A máquina de entrega saiu deste serviço para a biblioteca webhook-delivery, que é dona do schema
-- webhook_delivery e do próprio histórico Flyway. Esta migração é a ÚNICA vez que este serviço toca
-- naquele schema: move o que existe para a forma da V1 da lib, e a partir daqui a lib migra sozinha
-- (flyway.baseline-on-migrate=true, baseline-version=1 no application.yml).
--
-- job_locks fica aqui: é lease de jobs deste serviço (reconciliação), não da entrega.

CREATE SCHEMA IF NOT EXISTS webhook_delivery;

ALTER TABLE webhook.webhook_endpoints SET SCHEMA webhook_delivery;
ALTER TABLE webhook.deliveries SET SCHEMA webhook_delivery;

-- Endpoints: PK deixa de ser o tenant. Cada linha existente vira o "endpoint único" do tenant,
-- inscrito em tudo — exatamente o comportamento que tinha.
ALTER TABLE webhook_delivery.webhook_endpoints DROP CONSTRAINT webhook_endpoints_pkey;
ALTER TABLE webhook_delivery.webhook_endpoints ADD COLUMN id UUID;
UPDATE webhook_delivery.webhook_endpoints SET id = gen_random_uuid();
ALTER TABLE webhook_delivery.webhook_endpoints ALTER COLUMN id SET NOT NULL;
ALTER TABLE webhook_delivery.webhook_endpoints ADD PRIMARY KEY (id);
ALTER TABLE webhook_delivery.webhook_endpoints ADD COLUMN events TEXT[] NOT NULL DEFAULT '{*}';
CREATE INDEX idx_webhook_endpoints_tenant_active ON webhook_delivery.webhook_endpoints (tenant_id, active);

-- Linhas de antes da V005 nunca tiveram segredo por tenant: assinavam com o segredo GLOBAL de dev
-- (barrier.webhook.secret), que nem migra — não é coluna, era config. Sem isto, a lib filtra
-- secret() == null em resolveSigningMaterial e toda entrega desses tenants nasce morta (DEAD) na
-- primeira tentativa, silenciosamente. O valor aqui é só para não deixar a coluna NOT NULL-em-uso
-- vazia: é operacionalmente o segredo global antigo que valia até aqui, então cada um desses
-- tenants PRECISA rotacionar (POST /v1/webhook-endpoints/{tenantId}/rotate-secret) para receber um
-- segredo próprio e reconfigurar o parceiro — o valor gerado abaixo nunca foi exposto a ninguém e
-- não deve ser tratado como o segredo "real" do tenant.
UPDATE webhook_delivery.webhook_endpoints
   SET secret = encode(sha256((gen_random_uuid()::text || gen_random_uuid()::text)::bytea), 'hex')
 WHERE secret IS NULL;

-- Entregas: aggregate_id, event_type e endpoint_id. As existentes são todas de assessment.completed
-- (o outro tópico, risk_level_changed, nasceu depois da V008 e nunca teve entrega gravada com tipo)
-- e apontam para o endpoint único do seu tenant.
ALTER TABLE webhook_delivery.deliveries RENAME COLUMN assessment_id TO aggregate_id;
ALTER TABLE webhook_delivery.deliveries ADD COLUMN event_type VARCHAR(120) NOT NULL DEFAULT 'barrier.assessment.completed';
ALTER TABLE webhook_delivery.deliveries ALTER COLUMN event_type DROP DEFAULT;
ALTER TABLE webhook_delivery.deliveries ADD COLUMN endpoint_id UUID;
UPDATE webhook_delivery.deliveries d
   SET endpoint_id = e.id
  FROM webhook_delivery.webhook_endpoints e
 WHERE e.tenant_id = d.tenant_id;
-- Entrega sem tenant ou de tenant sem endpoint (anteriores à V004): não há para onde entregar e
-- nunca houve; recebem um id sintético para satisfazer o NOT NULL sem inventar destino.
UPDATE webhook_delivery.deliveries SET endpoint_id = '00000000-0000-0000-0000-000000000000' WHERE endpoint_id IS NULL;
UPDATE webhook_delivery.deliveries SET tenant_id = 'desconhecido' WHERE tenant_id IS NULL;
-- Essas entregas não têm endpoint de verdade (o id sintético não existe em webhook_endpoints) e
-- ninguém vai retomá-las: se ficassem PENDING/FAILED, claimDue tentaria assinar com um endpoint
-- inexistente. DEAD com o motivo é honesto sobre o que aconteceu, em vez de deixá-las presas.
UPDATE webhook_delivery.deliveries
   SET status = 'DEAD', last_error = 'sem endpoint na migracao V009', next_attempt_at = NULL, claimed_at = NULL
 WHERE endpoint_id = '00000000-0000-0000-0000-000000000000'
   AND status IN ('PENDING', 'FAILED');
ALTER TABLE webhook_delivery.deliveries ALTER COLUMN endpoint_id SET NOT NULL;
ALTER TABLE webhook_delivery.deliveries ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE webhook_delivery.deliveries DROP CONSTRAINT deliveries_event_id_key;
ALTER TABLE webhook_delivery.deliveries ADD CONSTRAINT uq_deliveries_event_endpoint UNIQUE (event_id, endpoint_id);
CREATE INDEX idx_deliveries_event ON webhook_delivery.deliveries (event_id);
