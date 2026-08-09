-- ScreeningCoverageRiskRule: barra aprovação automática quando o screening rodou sem cobertura
-- de listas (importação falhou / lista vencida). É regulatória — RegulatoryRiskRules impede que
-- seja desligada pelo registry; a linha existe para o registry ficar completo e a criticidade
-- aparecer na API de gestão.
INSERT INTO risk_rule_registry (rule_code, description, criticality) VALUES
    ('SCREENING_COVERAGE', 'Screening sem cobertura de listas restritivas', 'REVIEW');
