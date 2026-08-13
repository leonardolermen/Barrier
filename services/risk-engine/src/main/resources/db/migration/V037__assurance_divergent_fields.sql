-- Campos que a documentoscopia leu do documento e que divergem do que o cadastro/Subject
-- declaram. Coluna própria, separada de `detail` (V035): `detail` é a mensagem humana do
-- provedor (texto livre, formato dele) — usá-la também para sinalizar divergência obrigava
-- concatenar sem limite conhecido contra uma coluna VARCHAR(400) e arriscava truncar (ou
-- estourar) a mensagem original do provedor numa submissão real. Aqui é lista curta e fixa de
-- enum (NAME, BIRTH_DATE), nunca carrega o valor declarado nem o extraído — só quais campos
-- divergiram. Sem DOCUMENT: o número lido do RG/CNH não é comparável com o CPF/CNPJ do Subject
-- (ADR-0011) — grandezas diferentes, ver DivergentField.
ALTER TABLE identity_assurance_checks
    ADD COLUMN divergent_fields VARCHAR(60);
