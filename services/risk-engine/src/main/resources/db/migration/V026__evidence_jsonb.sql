-- Colunas de evidência deixam de ser VARCHAR(n).
--
-- Descoberto exercitando a API com um CNPJ real: o Banco do Brasil tem 41 sócios no QSA, o JSON
-- passa de 4000 caracteres, e o INSERT falha com "value too long". O efeito não é perder a
-- evidência — é a avaliação inteira falhar, esgotar as 5 tentativas e terminar em
-- FALHA_PROCESSAMENTO. Empresas grandes, que são justamente as de estrutura societária mais
-- complexa, não conseguiam ser onboardadas.
--
-- O mesmo teto existia nas outras colunas de evidência, e todas falham do mesmo jeito: um cliente
-- com muitos apontamentos (o de maior risco) estoura `hits_json`; uma decisão com muitas regras
-- disparadas estoura `results_json`. O limite estava exatamente onde a informação mais importa.
--
-- JSONB e não TEXT nas três colunas de JSON: além de não ter teto, permite consultar a evidência
-- ("quais avaliações tiveram apontamento de PEP?"), que é pergunta de auditoria, não de aplicação.
-- `factors` continua texto puro — é uma lista de linhas legíveis, não JSON.
ALTER TABLE subject_profiles
    ALTER COLUMN partners_json TYPE JSONB USING partners_json::jsonb;

ALTER TABLE screening_results
    ALTER COLUMN hits_json TYPE JSONB USING hits_json::jsonb;

ALTER TABLE risk_scores
    ALTER COLUMN results_json TYPE JSONB USING results_json::jsonb;

ALTER TABLE assessments
    ALTER COLUMN factors TYPE TEXT;
