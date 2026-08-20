-- Índice para buscar CANDIDATOS de match por nome, em vez de carregar a base inteira.
--
-- O PROBLEMA: `findNameEntries()` era `jpa.findAll()`. O FuzzyNameWatchlistProvider materializava
-- **toda** a tabela em heap e rodava a comparação token a token sobre ela, uma vez por avaliação.
-- Em produção com OFAC (SDN+ALT, cada apelido é uma linha) + CSNU + CEIS/CNEP + PEP da CGU, isso é
-- da ordem de 10^5–10^6 linhas materializadas por avaliação.
--
-- O que torna isto o pior item do repositório não é a lentidão: é que o custo cresce com a
-- COBERTURA DE LISTAS. Melhorar o compliance degradava a plataforma — um incentivo invertido
-- dentro do sistema.
--
-- A ESTRATÉGIA: blocking por token com trigramas. O operador `<%` (word_similarity) responde
-- exatamente à pergunta certa aqui — "algum token deste nome é parecido com o token que estou
-- procurando?" —, e é o que o algoritmo faz: cobertura token a token, não similaridade da string
-- inteira. Similaridade de string inteira seria a métrica errada, porque as listas publicam
-- "SOBRENOME, Nome" e o cadastro traz "Nome Sobrenome".
--
-- FAIL-OPEN DE PROPÓSITO: `name_normalized` nasce NULL e a consulta de candidatos inclui
-- explicitamente as linhas com NULL. Enquanto a coluna não estiver preenchida, o comportamento é
-- o de hoje (varredura completa) — mais lento, nunca menos abrangente. Perder um sancionado por
-- causa de uma coluna ainda não preenchida seria trocar um problema de performance por um de
-- corretude, que é a única troca que não se pode fazer aqui.
--
-- A convergência é imediata na prática: `WatchlistImporter` é ApplicationRunner, então toda subida
-- reimporta e `replaceSource` reescreve as linhas com o valor normalizado pelo NameNormalizer do
-- Java. Normalizar em SQL criaria uma segunda implementação da normalização, e duas divergem.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE watchlist_entries ADD COLUMN name_normalized VARCHAR(300);

COMMENT ON COLUMN watchlist_entries.name_normalized IS
    'Nome normalizado pelo NameNormalizer do Java (maiúsculas, sem acento, sem pontuação). '
    'Preenchido na importação; NULL faz a linha entrar como candidata sempre (fail-open).';

-- GIN + gin_trgm_ops é o que o operador <% usa. GiST seria menor e mais lento na consulta; aqui a
-- leitura é o caminho quente (uma busca por avaliação) e a escrita acontece uma vez por dia.
CREATE INDEX idx_watchlist_entries_name_trgm
    ON watchlist_entries USING gin (name_normalized gin_trgm_ops);

-- Enquanto houver linha sem normalizar, a consulta precisa achá-la rápido para não degradar para
-- seq scan da tabela toda. Índice parcial: ocupa quase nada depois que a primeira importação
-- preenche todas as linhas.
CREATE INDEX idx_watchlist_entries_name_pendente
    ON watchlist_entries (id) WHERE name_normalized IS NULL;
