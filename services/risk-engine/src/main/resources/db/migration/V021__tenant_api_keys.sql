-- Autenticação do tenant por API key. Até aqui o tenant vinha do header X-Client-Id, que é
-- autodeclarado: bastava conhecer (ou adivinhar) um id para ler e DECIDIR avaliações de outro
-- cliente. A partir daqui o tenant é derivado da credencial e o header é ignorado.
--
-- O segredo NÃO é guardado: só o hash. Um dump desta tabela não permite autenticar como ninguém.
-- `key_id` é a parte pública da chave, indexada, para localizar a linha sem varrer hashes.
CREATE TABLE tenant_api_keys (
    id          UUID PRIMARY KEY,
    tenant_id   VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    key_id      VARCHAR(40)  NOT NULL,
    secret_hash VARCHAR(64)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ,
    CONSTRAINT uq_tenant_api_keys_key_id UNIQUE (key_id)
);

CREATE INDEX idx_tenant_api_keys_tenant ON tenant_api_keys (tenant_id);

-- Sem seed de chave: uma credencial conhecida versionada no repositório é exatamente o problema
-- do 'dev-secret' do webhook. Ambientes de dev/teste emitem a própria chave na subida.
