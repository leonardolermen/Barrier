# ADR-0010: Watchlists são ingeridas (arquivo → tabela), não consultadas por request

- **Status:** Aceito
- **Data:** 2026-07-07

## Contexto

O screening bate os clientes contra listas restritivas: sanções (OFAC/ONU/UE), inidôneas
(CGU: CEIS/CNEP/CEPIM) e PEP. Diferente de um bureau de identidade (que responde por
documento consultado), essas listas são **arquivos publicados** (CSV/XML/JSON) atualizados
periodicamente — feitas para download em lote, não para consulta registro a registro. Várias
sequer oferecem API de consulta pontual.

## Decisão

Vamos **ingerir** as listas: um importador baixa/parseia cada fonte e faz *upsert* numa
tabela local `watchlist_entries`; o screening casa contra essa tabela.

- `WatchlistSource` (Strategy de fonte): cada lista sabe se buscar e devolve registros.
- `WatchlistImporter`: roda na subida e periodicamente (`@Scheduled`); por fonte, substitui
  as entradas daquela fonte (delete + insert) e guarda a **versão da lista** e o instante da
  importação.
- `LocalWatchlistProvider implements WatchlistProvider`: lê `watchlist_entries` — o
  `ScreeningService` não muda (continua agregando `WatchlistProvider`s).

## Alternativas consideradas

- **Consulta por request a cada screening** — inviável: a maioria das listas não tem API de
  consulta; dependeria da fonte estar no ar na hora da decisão; sem controle de versão.

## Consequências

- **Positivas:** match local rápido e offline; auditável (qual lista, qual versão/data foi
  usada na decisão); controle total do algoritmo de match.
- **Negativas / custos:** pipeline de ingestão a manter (download, parse, agendamento, falhas
  por fonte); dados podem ficar defasados entre importações.
- **Mitigações:** import idempotente por fonte; falha de uma fonte não derruba as outras;
  guardar `list_version`/`imported_at` para auditoria e alarme de defasagem.

## Dois tipos de match (e a ordem de implementação)

- **Por documento** (CGU: CEIS/CNEP/CEPIM têm CPF/CNPJ) → match exato. **Implementado primeiro.**
- **Por nome** (OFAC/ONU/UE, sem CPF/CNPJ) → exige normalização + fuzzy + aliases. Fase seguinte.

O primeiro corte usa uma fonte de arquivo (semente em `resources/watchlists/`) para exercitar
todo o pipeline de forma determinística; uma fonte HTTP real (CGU/OFAC) é um `WatchlistSource`
adicional, sem mudar o resto.
