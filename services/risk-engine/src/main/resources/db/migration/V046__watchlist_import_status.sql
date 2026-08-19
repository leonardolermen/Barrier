-- Cobertura de watchlist deixa de ser estado de instância.
--
-- O `WatchlistImportStatus` era um ConcurrentHashMap em memória, com o racional escrito de que
-- "o que interessa é se ESTA instância tem cobertura utilizável". Esse racional não se sustenta
-- contra a arquitetura: `replaceSource` grava em `watchlist_entries`, que é uma tabela
-- COMPARTILHADA. A lista é global; só o status dela era local.
--
-- Consequência, e ela já existe hoje sem nenhum lock envolvido: com 5 réplicas, se a importação
-- falha em UMA delas (blip de rede no download), aquela réplica se dá por descoberta e a
-- `ScreeningCoverageRiskRule` força REVIEW em tudo que ela atender — mesmo com a tabela
-- integralmente populada pelas outras quatro. Um quinto do tráfego indo para revisão manual por
-- um erro de medição, não de dado.
--
-- E é pré-requisito do lock (V045): com a importação virando singleton, quatro réplicas passam a
-- nunca importar. Se o status continuasse em memória, essas quatro nasceriam com cobertura vazia
-- e mandariam 100% das avaliações para revisão. A correção do lock, sozinha, produziria um
-- incidente pior que o problema que ela resolve — por isso as duas mudanças são a mesma migration.
CREATE TABLE watchlist_import_status (
    source          VARCHAR(50) PRIMARY KEY,
    provides        VARCHAR(200) NOT NULL,
    last_success_at TIMESTAMPTZ,
    records         INTEGER      NOT NULL DEFAULT 0,
    last_error      TEXT,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE watchlist_import_status IS
    'Resultado da última importação por fonte. Compartilhado entre réplicas: a lista em '
    'watchlist_entries também é.';
COMMENT ON COLUMN watchlist_import_status.provides IS
    'Categorias (MatchType) que a fonte cobre quando importa com sucesso, separadas por vírgula.';
COMMENT ON COLUMN watchlist_import_status.last_success_at IS
    'Última importação bem-sucedida. NULL = nunca houve. Falha preserva este valor: a base ainda '
    'tem a versão anterior, que segue utilizável até vencer por barrier.watchlist.max-age.';
COMMENT ON COLUMN watchlist_import_status.last_error IS
    'Motivo da última falha; NULL quando a última tentativa deu certo.';
