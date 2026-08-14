-- CorporateStructureCoverageRiskRule: barra aprovação automática de PJ quando o bureau confirmou
-- a empresa mas não trouxe QSA (basic_data da BigBoost, ligada em application-prod.yml, não traz
-- quadro societário). É regulatória — RegulatoryRiskRules impede que seja desligada pelo
-- registry; a linha existe para o registry ficar completo e a criticidade aparecer na API de
-- gestão, mesmo padrão de V020 (SCREENING_COVERAGE).
INSERT INTO risk_rule_registry (rule_code, description, criticality) VALUES
    ('CORPORATE_STRUCTURE_COVERAGE', 'PJ confirmada pelo bureau sem quadro societário (QSA)', 'REVIEW');
