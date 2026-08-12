-- Consentimento é por verificação, não por cadastro: a prova que a LGPD pede é de consentimento
-- para AQUELA finalidade no momento DAQUELE tratamento. Um flag global no subject não prova isso.
ALTER TABLE identity_assurance_checks
    ADD COLUMN consent_reference  VARCHAR(120),
    ADD COLUMN consent_purpose    VARCHAR(120),
    ADD COLUMN consent_granted_at TIMESTAMPTZ;
