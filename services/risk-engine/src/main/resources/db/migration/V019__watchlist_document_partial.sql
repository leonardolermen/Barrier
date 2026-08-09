-- Algumas listas publicam o CPF PARCIALMENTE MASCARADO por exigência de privacidade — é o caso
-- do cadastro de PEP da CGU, que divulga "***.123.456-**", revelando só os 6 dígitos centrais.
--
-- Esses 6 dígitos não servem para match exato (a coluna `document` continua nula nesses casos,
-- senão o LocalWatchlistProvider casaria errado), mas servem como DISCRIMINADOR do match por
-- nome: sem eles, "JOSE SILVA" casaria com todo homônimo da lista e cada acerto viraria revisão
-- manual. Com eles, o espaço de colisão cai ~1 milhão de vezes.
ALTER TABLE watchlist_entries ADD COLUMN document_partial VARCHAR(20);

CREATE INDEX idx_watchlist_entries_document_partial ON watchlist_entries (document_partial);
