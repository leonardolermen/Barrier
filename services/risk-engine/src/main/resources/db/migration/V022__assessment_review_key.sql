-- Qual credencial tomou a decisão manual. `reviewed_by` continua sendo texto informado pelo
-- chamador (a API key identifica o sistema cliente, não a pessoa) — separar os dois deixa claro
-- para o auditor qual parte da atribuição o sistema garante e qual é autodeclarada.
ALTER TABLE assessments ADD COLUMN reviewed_by_key VARCHAR(120);
