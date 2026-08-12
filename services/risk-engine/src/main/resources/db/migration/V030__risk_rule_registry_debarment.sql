-- DebarmentRiskRule: inidoneidade/impedimento em licitação (CEIS/CNEP) deixa de ser tratada como
-- sanção financeira. Antes esses registros entravam como SANCTION e produziam REJECT automático:
-- recusa de relacionamento bancário a empresa que a lei não impede de ser cliente.
--
-- NÃO entra em RegulatoryRiskRules de propósito: nenhuma norma do Bacen manda recusar conta por
-- inidoneidade em licitação, então esta é regra de apetite de risco — pode ser desligada pelo
-- registry como qualquer outra, diferente de SANCTION/PEP/IDENTITY.
INSERT INTO risk_rule_registry (rule_code, description, criticality) VALUES
    ('DEBARMENT', 'Inidoneidade/impedimento de contratar com a administração pública (CEIS/CNEP)', 'ALERT');

-- A descrição de SANCTION mencionava a CGU, que a partir daqui não produz mais SANCTION.
UPDATE risk_rule_registry
   SET description = 'Apontamento em lista de sanções financeiras (OFAC/ONU)'
 WHERE rule_code = 'SANCTION';
