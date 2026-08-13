-- Documentoscopia e biometria: guarda o RESULTADO, nunca a imagem.
--
-- Não existe coluna para foto, selfie ou template biométrico, e isso é a decisão (ADR-0016), não
-- uma etapa pendente: base biométrica vazada não se revoga — ninguém troca de rosto. O acervo que
-- não existe não vaza, e sem imagem nem template o Barrier trata dado pessoal comum em vez de dado
-- sensível (art. 5º, II da LGPD).
--
-- O que fica é o mesmo padrão já usado para o bureau em V031: desfecho + score + ponteiro para a
-- consulta no provedor, que mantém a cópia íntegra sob o controle de acesso dele.
CREATE TABLE identity_assurance_checks (
    id                UUID PRIMARY KEY,
    subject_id        UUID         NOT NULL REFERENCES subjects (id),
    tenant_id         VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    -- DOCUMENT (documentoscopia) | BIOMETRIC (face match + prova de vida)
    kind              VARCHAR(20)  NOT NULL,
    outcome           VARCHAR(20)  NOT NULL,
    -- 0..100; nulo quando o provedor só devolve desfecho
    score             INT,
    provider          VARCHAR(60)  NOT NULL,
    -- id da consulta no provedor: é o que permite reconciliar a fatura e recuperar a evidência
    provider_reference VARCHAR(120),
    -- versão do modelo/algoritmo. Sem ela, "bateu 98%" não significa nada seis meses depois, e
    -- comparar dois resultados de épocas diferentes é comparar réguas diferentes.
    algorithm_version VARCHAR(60),
    -- SHA-256 do que foi submetido. Não é dado biométrico (não reconstrói rosto nem documento) e
    -- é o que permite provar, numa contestação, que a imagem apresentada depois é a mesma que foi
    -- analisada — sem guardar a imagem para isso.
    submitted_hash    VARCHAR(64),
    detail            VARCHAR(400),
    checked_at        TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_assurance_subject ON identity_assurance_checks (subject_id, tenant_id, kind, checked_at DESC);
