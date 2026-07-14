-- Dados de cadastro do subject exigidos pela Resolução CMN 4.753 (completude cadastral).
-- 1:1 com subjects; colunas nullable porque PF e PJ usam campos diferentes.
CREATE TABLE subject_profiles (
    id                            UUID PRIMARY KEY,
    subject_id                    UUID NOT NULL REFERENCES subjects (id),
    birth_date                    DATE,          -- PF
    founding_date                 DATE,          -- PJ
    nationality                   VARCHAR(60),   -- PF
    occupation                    VARCHAR(120),  -- PF
    declared_income               NUMERIC(16, 2),-- PF: renda mensal / PJ: faturamento anual
    address_street                VARCHAR(200),
    address_number                VARCHAR(20),
    address_complement            VARCHAR(100),
    address_district              VARCHAR(100),
    address_city                  VARCHAR(100),
    address_state                 VARCHAR(2),
    address_zip_code              VARCHAR(10),
    phone                         VARCHAR(30),
    email                         VARCHAR(160),
    cnae_code                     VARCHAR(10),   -- PJ
    cnae_description              VARCHAR(200),  -- PJ
    share_capital                 NUMERIC(16, 2),-- PJ
    legal_representative_name     VARCHAR(200),  -- PJ
    legal_representative_document VARCHAR(20),   -- PJ
    partners_json                 VARCHAR(4000), -- PJ: QSA, mesmo padrão de hits_json/results_json
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_subject_profiles_subject UNIQUE (subject_id)
);

CREATE INDEX idx_subject_profiles_subject ON subject_profiles (subject_id);
