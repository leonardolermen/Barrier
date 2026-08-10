-- Proveniência do cadastro por tenant.
--
-- O cadastro era GLOBAL: uma linha por subject, gravável e legível por qualquer tenant que tivesse
-- vínculo — e o vínculo nasce de um simples POST /v1/assessments. Isso produzia duas falhas, uma
-- em cada direção:
--
-- 1. LEITURA. `PUT /v1/subjects/{doc}/profile` devolve o perfil depois do merge, e um patch vazio
--    não altera nada. Bastavam duas chamadas — POST para criar o vínculo, PUT {} para ler — e um
--    parceiro obtinha endereço, telefone, e-mail, data de nascimento, renda declarada e
--    representante legal do cliente de OUTRO parceiro. Sem rate limit, em escala industrial.
--
-- 2. ESCRITA. Um tenant completava o cadastro de um subject alheio e, com isso, satisfazia o gate
--    de `RegistrationCompleteness` de outro parceiro — induzindo aprovação automática numa
--    avaliação que deveria cair em revisão por cadastro incompleto.
--
-- A chave passa a ser (subject_id, tenant_id): cada parceiro enxerga e altera apenas o que ele
-- mesmo declarou. O `Subject` continua global (é o que sustenta a deduplicação por documento); o
-- que deixa de ser compartilhado é o dossiê.
--
-- Backfill: cada perfil existente é atribuído ao tenant que primeiro se vinculou ao subject —
-- a melhor aproximação disponível de quem o declarou. Sem vínculo nenhum, fica com o tenant
-- 'default' (semeado em V009).
ALTER TABLE subject_profiles ADD COLUMN tenant_id VARCHAR(40);

UPDATE subject_profiles p
   SET tenant_id = COALESCE(
       (SELECT ts.tenant_id
          FROM tenant_subjects ts
         WHERE ts.subject_id = p.subject_id
         ORDER BY ts.first_seen_at, ts.tenant_id
         LIMIT 1),
       'default');

ALTER TABLE subject_profiles ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE subject_profiles
    ADD CONSTRAINT fk_subject_profiles_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id);

-- A unicidade por subject era exatamente o que forçava o compartilhamento.
ALTER TABLE subject_profiles DROP CONSTRAINT uq_subject_profiles_subject;

ALTER TABLE subject_profiles
    ADD CONSTRAINT uq_subject_profiles_subject_tenant UNIQUE (subject_id, tenant_id);
