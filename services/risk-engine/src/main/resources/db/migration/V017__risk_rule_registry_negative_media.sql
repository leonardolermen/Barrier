-- NegativeMediaRiskRule (código NEGATIVE_MEDIA) chegou depois de V016; sem esta linha o
-- registry ficaria fail-open pra ela por omissão (correto, mas registry incompleto).
INSERT INTO risk_rule_registry (rule_code, description, criticality) VALUES
    ('NEGATIVE_MEDIA', 'Apontamento em mídia negativa — EDD', 'REVIEW');
