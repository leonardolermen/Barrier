-- Proveniência da decisão: provar o que rodou, contra o quê, e com qual evidência.
--
-- Três lacunas de auditoria, todas com o mesmo formato — a trilha registrava o resultado e não o
-- procedimento:
--
-- 1. SÓ AS REGRAS QUE DISPARARAM eram gravadas. "A regra de sanção não aparece" tinha três
--    leituras indistinguíveis: rodou e o cliente estava limpo, estava desligada no registry, ou a
--    lista estava vazia. Só a primeira é aceitável, e não havia como diferenciá-las.
--    `evaluated_json` passa a guardar TODAS as regras do motor com o desfecho de cada uma
--    (TRIGGERED / NOT_TRIGGERED / SUPPRESSED).
--
-- 2. QUAL EVIDÊNCIA alimentou a decisão. Uma avaliação retentada deixa várias linhas de
--    identity_checks e screening_results com o mesmo assessment_id, e nada dizia qual delas valeu.
--
-- 3. CONTRA QUAL LISTA o screening rodou. `watchlist_entries.list_version` existia e nunca era
--    copiado para a decisão — e a base é substituída diariamente (replaceSource). Passado um mês,
--    ninguém consegue dizer se o nome estava na lista naquele dia.
--
-- `results_json` é preservado como está: ele é lido para reconstruir decisões antigas, e mudar sua
-- forma no lugar quebraria exatamente a leitura do histórico que este trabalho existe para
-- melhorar. A redundância entre ele e `evaluated_json` é o preço da compatibilidade.
ALTER TABLE risk_scores ADD COLUMN evaluated_json JSONB;
ALTER TABLE risk_scores ADD COLUMN identity_check_id UUID;
ALTER TABLE risk_scores ADD COLUMN screening_result_id UUID;

ALTER TABLE screening_results ADD COLUMN sources_json JSONB;

COMMENT ON COLUMN risk_scores.evaluated_json IS
    'Todas as regras avaliadas com o desfecho de cada uma; prova que um controle rodou e passou';
COMMENT ON COLUMN screening_results.sources_json IS
    'Fonte -> versão da lista consultada no momento do screening (snapshot para auditoria)';
