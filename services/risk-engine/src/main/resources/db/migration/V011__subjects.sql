-- Subject = o cliente final (CPF/CNPJ). Um registro por documento, compartilhado.
CREATE TABLE subjects (
    id            UUID PRIMARY KEY,
    document_type VARCHAR(10)  NOT NULL,
    document      VARCHAR(20)  NOT NULL,
    name          VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_subjects_document UNIQUE (document_type, document)
);

-- Associação de visibilidade: uma empresa só "enxerga" um subject se existe este vínculo.
CREATE TABLE tenant_subjects (
    id            UUID PRIMARY KEY,
    tenant_id     VARCHAR(40) NOT NULL REFERENCES tenants (id),
    subject_id    UUID        NOT NULL REFERENCES subjects (id),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_subjects UNIQUE (tenant_id, subject_id)
);

CREATE INDEX idx_tenant_subjects_tenant ON tenant_subjects (tenant_id);
