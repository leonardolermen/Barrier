-- Histórico de qual device (fingerprint/deviceId) foi usado em qual avaliação/subject, por
-- tenant. Alimenta o sinal de reuso: o mesmo device criando várias contas em pouco tempo é
-- um padrão clássico de fraude (múltiplas contas, laranjas).
CREATE TABLE device_seen (
    id         UUID PRIMARY KEY,
    tenant_id  VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    device_id  VARCHAR(200) NOT NULL,
    subject_id UUID         NOT NULL,
    seen_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_device_seen_tenant_device ON device_seen (tenant_id, device_id);
