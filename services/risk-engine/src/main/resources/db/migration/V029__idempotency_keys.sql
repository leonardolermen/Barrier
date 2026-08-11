-- Idempotência do intake: o mesmo POST reenviado com a mesma chave devolve a mesma avaliação.
--
-- Sem isto, um retry do cliente (timeout de rede, redeploy, fila de reprocessamento) cria duas
-- avaliações do mesmo cliente final: dois custos de bureau, dois webhooks e — pior — duas decisões
-- que podem divergir, porque as consultas externas acontecem em momentos diferentes. Divergir torna
-- o retry um oráculo: quem quiser ser aprovado tenta até o bureau falhar.
--
-- `assessment_id` nasce NULO e é preenchido depois que a avaliação existe: a reserva da chave é
-- gravada em transação própria, antes da avaliação, para que duas requisições concorrentes com a
-- mesma chave disputem uma linha só. Por isso não há FK para `assessments` — a reserva é commitada
-- quando a avaliação ainda não foi. Enquanto está nula, a chave está "em andamento" e um segundo
-- POST recebe 409 em vez de uma resposta parcial.
--
-- `request_hash` (SHA-256 do tenant + tipo + documento + nome) existe para detectar reuso de chave
-- com conteúdo diferente, que é erro do cliente e não pode ser servido pela resposta antiga. É hash
-- e não os campos: a tabela não precisa guardar CPF/nome de novo (LGPD — guardar o mínimo).
CREATE TABLE idempotency_keys (
    tenant_id       VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    assessment_id   UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idempotency_key)
);

-- Suporta a limpeza periódica das chaves fora da janela.
CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);
