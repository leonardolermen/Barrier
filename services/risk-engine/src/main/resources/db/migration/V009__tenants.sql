-- Tenant = cliente da API (empresa que consome o Barrier). O header X-Client-Id mapeia para
-- o id do tenant. A autenticação por API key virá depois e passará a derivar o tenant da key.
CREATE TABLE tenants (
    id         VARCHAR(40) PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO tenants (id, name) VALUES ('default', 'Tenant padrão (desenvolvimento)');
