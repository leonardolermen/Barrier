-- Rastro da consulta ao bureau na trilha de identidade.
--
-- Até aqui, identity_checks guardava provider, status, detail e checked_at — tudo afirmação nossa
-- sobre nós mesmos. "Consultamos a Receita via BigBoost" não era verificável contra ninguém: numa
-- inspeção, contra o extrato do provedor, ou numa contestação de cliente. É a mesma lacuna que
-- screening_results.sources_json fechou para as listas (fonte → versão consultada), deixada aberta
-- só no bureau.
--
-- provider_reference: identificador da consulta do lado do provedor (QueryId da BigDataCorp).
-- Opaco, sem PII, e é o ponteiro para a cópia íntegra que o provedor mantém sob o controle de
-- acesso dele. Nulo quando a fonte não fornece — a BrasilAPI não tem identificador de consulta, e
-- registrar isso como ausência é mais honesto que inventar um id nosso.
--
-- raw_response: a resposta do bureau, em JSONB, COM REDAÇÃO dos campos que o projeto já decidiu
-- não guardar (nome da mãe é fator de autenticação; a decisão de guardar só o resultado da
-- comparação está documentada no DTO desde que ele existe). Guardar o payload é o que permite
-- responder "foi isto que o bureau respondeu naquele dia" sem depender de refazer a consulta —
-- que hoje responderia outra coisa.
--
-- ⚠️ Isto passa a guardar dado pessoal por avaliação. Duas dependências da Fase 6 valem
-- explicitamente para esta coluna: retenção de 10 anos e criptografia em repouso. Até lá, é
-- desligável por config (barrier.identity.store-raw-response).
ALTER TABLE identity_checks ADD COLUMN provider_reference VARCHAR(120);
ALTER TABLE identity_checks ADD COLUMN raw_response JSONB;

COMMENT ON COLUMN identity_checks.provider_reference IS
    'Id da consulta no provedor (ex.: QueryId da BigDataCorp); nulo quando a fonte não fornece';
COMMENT ON COLUMN identity_checks.raw_response IS
    'Resposta do bureau com redação dos campos sensíveis — evidência de auditoria (LGPD: ver Fase 6)';
