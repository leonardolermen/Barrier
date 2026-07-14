# ADR-0013: Fail-fast em produção se só a watchlist SEED estiver ativa

- **Status:** Aceito
- **Data:** 2026-07-13

## Contexto

`CeisWatchlistSource`, `CnepWatchlistSource` e `OfacWatchlistSource` (ver
[ADR-0010](0010-watchlists-ingeridas.md)) só existem como bean quando
`barrier.watchlist.cgu.enabled`/`barrier.watchlist.ofac.enabled` são `true` — desligadas por
padrão para dev/testes não dependerem de rede externa. Isso significa que um deploy de produção
que esqueça de habilitar essas flags sobe **silenciosamente** rodando só com o CSV seed
(`SeedFileWatchlistSource`), sem nenhum aviso — um gap de compliance invisível até alguém notar
que o screening PEP/sanções está praticamente vazio.

## Decisão

Adicionar `WatchlistReadinessGuard` (`ApplicationRunner`), que inspeciona quais
`WatchlistSource` beans o Spring efetivamente instanciou:

- Se a única fonte ativa for `SEED` **e** o profile `prod` estiver ativo, lança
  `IllegalStateException` no startup — a aplicação não sobe.
- Em qualquer outro profile, apenas loga `WARN`.

Também criamos `application-prod.yml`, que habilita `barrier.watchlist.cgu.enabled` e
`barrier.watchlist.ofac.enabled` por padrão quando `SPRING_PROFILES_ACTIVE=prod` — assim o
caminho feliz (subir com o profile `prod`) já vem correto, e o guard é a rede de segurança para
quem overridar essas flags manualmente sem perceber a implicação.

## Alternativas consideradas

- **Só documentar a necessidade de habilitar as flags em produção** — já era o estado atual
  (comentário no `application.yml`); provou-se insuficiente porque não há nada que force a
  checagem no momento do deploy.
- **Health check assíncrono (endpoint `/actuator/health`) em vez de fail-fast no startup** —
  degrada com mais elegância (a aplicação sobe e fica "unhealthy"), mas depende de alguém/algo
  monitorando o health check ativamente. Fail-fast no startup é mais forte: não tem como um
  deploy de produção ficar rodando incorretamente sem que alguém veja o crash imediatamente.
- **Verificar a contagem de linhas na tabela `watchlist_entries` em vez dos beans ativos** —
  mais próximo do dado real, mas exige uma query adicional e um valor de corte arbitrário; a
  checagem por bean é mais direta (reflete exatamente a configuração, não um efeito colateral
  dela) e não depende do resultado de um fetch de rede ainda não ter rodado.

## Consequências

- **Positivas:** impossível subir silenciosamente em produção com watchlist incompleta; o erro
  aparece imediatamente no log/processo de deploy, não depois de um cliente de risco passar
  batido.
- **Negativas / custos:** um operador que rode `SPRING_PROFILES_ACTIVE=prod` localmente (ex.:
  teste manual sem acesso à rede do CGU/OFAC) vai ver a aplicação recusar subir — precisa usar
  outro profile ou habilitar as flags manualmente para esse cenário.
- **Riscos e mitigações:** a checagem é por *bean ativo*, não por *dado importado com sucesso* —
  se CGU/OFAC estiverem habilitados mas o fetch falhar repetidamente (rede fora, endpoint
  mudou), o guard não pega isso; falhas de importação já são logadas em `ERROR` por
  `WatchlistImporter`, mas não impedem a subida. Ainda é um gap conhecido, delegado a
  monitoramento operacional (fica fora do escopo deste ADR).
