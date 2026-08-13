-- Veracidade do dado cadastral: separar "preenchido" de "verificado".
--
-- `RegistrationCompleteness` checava presença. Cadastro preenchido com dados plausíveis e
-- inventados — nascimento verossímil, telefone que existe mas não é do cliente, endereço de
-- terceiro — satisfazia o gate e liberava APROVAÇÃO AUTOMÁTICA. O controle rodava, produzia
-- evidência de que rodou, e não verificava nada: é o mesmo padrão de falha do stub de bureau que
-- aprovava todo mundo, aplicado ao cadastro.
--
-- A verificação é por (subject, tenant, campo) porque o cadastro já é por (subject, tenant) desde
-- a V024: um parceiro não herda a verificação feita por outro. Herdar seria reintroduzir, por uma
-- porta lateral, a indução de aprovação automática que a V024 fechou — bastaria um tenant validar
-- o telefone para o cadastro de todos os outros passar no gate.
CREATE TABLE subject_field_verifications (
    id           UUID PRIMARY KEY,
    subject_id   UUID         NOT NULL REFERENCES subjects (id),
    tenant_id    VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    field        VARCHAR(30)  NOT NULL,
    method       VARCHAR(30)  NOT NULL,
    -- valor verificado, normalizado. Verificação é de um VALOR, não de um campo: telefone
    -- confirmado por OTP e depois trocado por outro volta a ser não verificado, senão o cliente
    -- valida um número e usa outro.
    verified_value VARCHAR(200) NOT NULL,
    -- ponteiro para a prova (id do desafio de OTP, QueryId do bureau); o que sustenta a
    -- verificação numa contestação
    evidence     VARCHAR(200),
    verified_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_field_verification UNIQUE (subject_id, tenant_id, field)
);

CREATE INDEX idx_field_verifications_subject ON subject_field_verifications (subject_id, tenant_id);

-- Desafios de OTP.
--
-- O código vai como HASH, nunca em claro: quem lê a tabela (DBA, backup, dump de suporte) poderia
-- confirmar telefone e e-mail de qualquer cliente — mesma regra já aplicada às API keys dos
-- tenants em V012.
CREATE TABLE verification_challenges (
    id          UUID PRIMARY KEY,
    subject_id  UUID         NOT NULL REFERENCES subjects (id),
    tenant_id   VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    field       VARCHAR(30)  NOT NULL,
    target      VARCHAR(200) NOT NULL,
    code_hash   VARCHAR(64)  NOT NULL,
    -- tentativas restantes: sem teto, um código de 6 dígitos cai por força bruta em minutos
    attempts_left INT        NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_verification_challenges_lookup
    ON verification_challenges (subject_id, tenant_id, field, created_at DESC);
