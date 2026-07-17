INSERT INTO risk_rule_registry (rule_code, description, criticality) VALUES
    ('HISTORY', 'Histórico interno (chargeback, PIX devolvido, denúncia, conta encerrada por fraude)', 'ALERT'),
    ('CREDIT_SCORE_LOW', 'Score de crédito externo abaixo do limiar', 'ALERT');
