-- Endpoint de callback por tenant.
--
-- Até aqui o destino era UMA configuração global (`barrier.webhook.target-url`): com dois tenants,
-- o segundo recebia as decisões de KYC do primeiro. Não é um bug de borda — é vazamento
-- cross-tenant por desenho, e o dado vazado é o pior possível (documento, nome e o veredito de
-- PLD-FT de clientes de outra empresa).
--
-- Sem FK para `tenants`: aquela tabela pertence ao schema da risk-engine, e a regra do projeto é um
-- schema por serviço. O vínculo é lógico — o `tenant_id` vem no próprio evento.
--
-- `active` em vez de DELETE: desligar a entrega de um parceiro é operação reversível e auditável;
-- apagar a linha perderia o registro de que o endpoint existiu, que é o que se olha quando um
-- cliente reclama de callback não recebido.
CREATE TABLE webhook_endpoints (
    tenant_id  VARCHAR(40)  PRIMARY KEY,
    target_url VARCHAR(500) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
