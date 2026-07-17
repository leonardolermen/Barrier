INSERT INTO risk_rule_registry (rule_code, description, criticality) VALUES
    ('GEO_MISMATCH', 'IP de país/UF diferente do endereço cadastrado', 'ALERT'),
    ('DEVICE_REUSE', 'Mesmo device usado em múltiplos cadastros recentes', 'ALERT');
