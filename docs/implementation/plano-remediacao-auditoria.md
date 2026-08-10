# Plano de remediação — auditoria de KYC/PLD-FT

Documento vivo. Origem: auditoria crítica de `main` (commit `1033555`, 64 achados).
Serve para acompanhar a redução de risco até o sistema poder entrar em produção numa
instituição financeira regulada.

**Como usar:** cada item tem um critério de pronto verificável — não "implementar X", mas
"como sabemos que X está funcionando". Marque `[x]` só quando o critério for satisfeito e
houver teste cobrindo. Ao fechar um item, registre o commit ao lado.

**Regra que vale para todo este plano:** a auditoria encontrou um padrão, não uma lista de
bugs — *o sistema falha aberto em todo ponto onde deveria falhar fechado*, e a documentação
afirmava controles que o código não tinha. Qualquer item novo aqui deve ser avaliado contra
essas duas coisas antes de ser considerado pronto.

---

## Onde estamos

| | Auditoria inicial | 2ª auditoria | Agora | Δ |
|---|---|---|---|---|
| Testes | 141 (+2 erros) | 244 | **283** (0 falhas) | +142 |
| Achados 🔴 Critical | 27 | 20 (12 + 8 novos) | **8** | −19 |
| Nota geral | 3,2 | 4,0 | **≈6,0** | +2,8 |

> A 2ª auditoria (independente) reavaliou a nota para baixo — 4,0, não 5,2 — por dois motivos:
> encontrou quatro críticos novos que não estavam precificados (vazamento cross-tenant do cadastro,
> match por nome inoperante, stub como fallback, `@Version` decorativo), e observou que várias
> correções recentes eram **estruturalmente incompletas**: o `@Version` foi adicionado mas não
> protegia; o `CpfBureauReadinessGuard` protegia a subida mas não o runtime; o health de cobertura
> fechou um fail-open e abriu um risco de indisponibilidade total da frota.

Ramos entregues:

| Branch | Commit | Conteúdo |
|---|---|---|
| `fix/hardening-go-live` | `bdecade` | Fail-open de decisão, gate de admin, segredos, PII em log, comparação de nome |
| `feat/pep-watchlist-cgu` | `d671e72` | Fonte de PEP da CGU, cobertura de listas verificável e fail-closed |
| `feat/tenant-api-key` | `e1792cd` | Autenticação por API key; tenant derivado da credencial |
| `fix/processing-integrity` | — | Reivindicação exclusiva, transação por avaliação, estado de falha |
| `fix/audit-top10` | — | Proveniência do cadastro por tenant, match por nome token a token, stub fora da cadeia, versão real no save, timeouts e scheduler |

Notas por dimensão (0–10):

| Dimensão | Antes | Agora | Comentário |
|---|---|---|---|
| Arquitetura | 5,5 | 7,0 | Transação por item, posse com lease, falha isolada |
| Segurança | 1,5 | 5,0 | Auth de tenant, admin e segredos fechados; **cripto em repouso segue ausente** |
| KYC | 2,0 | 4,0 | Nome comparado; **PF ainda sem bureau** |
| Antifraude | 1,0 | 1,0 | Intocado |
| AML/Compliance | 2,5 | 4,0 | PEP existe; CSNU, CEIS e rescreening abertos |
| Escalabilidade | 2,0 | 2,0 | Intocado — ainda ~1–3 TPS/instância |
| Resiliência | 3,5 | 6,5 | Fail-closed + poison pill e duplicação resolvidos |
| Observabilidade | 2,0 | 2,5 | Só o health de cobertura |
| Auditoria | 4,0 | 4,5 | Evidência mais rica; reprodutibilidade ainda não |
| Explicabilidade | 6,0 | 6,5 | `MatchBasis` e motivo de cobertura na trilha |
| Privacidade | 2,5 | 3,5 | Logs limpos; cripto e cadastro compartilhado abertos |
| Testabilidade | 5,5 | 6,5 | Cada correção veio com teste de regressão |
| Governança | 4,0 | 5,0 | `compliance.md` deixou de afirmar o que não existe |

> A nota subiu, mas **o sistema continua não podendo ir para produção**. O que falta agora
> é estrutural (autenticação, escala, monitoramento contínuo), não pontual — e por isso as
> próximas ondas são mais caras que as duas primeiras.

---

## ✅ Fechado

### `fix/hardening-go-live` (`bdecade`)

- [x] `IDENTITY_UNAVAILABLE` força REVIEW — bureau indisponível era janela de aprovação automática
- [x] Regras regulatórias imunes ao kill switch do registry (motor + API, dupla camada)
- [x] Sanção por nome → REVIEW; por documento → REJECT (`MatchBasis`)
- [x] `X-Admin-Key` em `/v1/risk-rules` e `/v1/tenants/*/risk-config` + guard de startup
- [x] `WebhookSecretReadinessGuard` — impede subir com o `dev-secret` versionado
- [x] Nome do cliente fora dos logs; `prod` em `INFO`
- [x] Comparação nome × bureau (`NameSimilarity`, token a token)
- [x] `CpfBureauReadinessGuard` — prod não sobe com o stub de CPF

### `feat/pep-watchlist-cgu` (`d671e72`)

- [x] `PepWatchlistSource` — primeira fonte a produzir `MatchType.PEP`
- [x] CPF mascarado como discriminador (`document_partial`, V019)
- [x] `WatchlistImportStatus` + `WatchlistHealthIndicator`
- [x] Importação vazia não substitui a base
- [x] `ScreeningCoverageRiskRule` — sem cobertura, não aprova
- [x] `WatchlistReadinessGuard` exige cobertura de SANCTION **e** PEP

### `fix/audit-top10` — segunda auditoria

Cinco achados novos, quatro deles críticos. Origem: auditoria independente de `main` após
`fix/processing-integrity`.

- [x] **Vazamento cross-tenant do cadastro** 🔴
  `PUT /v1/subjects/{doc}/profile` devolvia o `SubjectProfile` depois do merge, e patch vazio não
  altera nada — com o vínculo criado por um `POST /v1/assessments`, duas chamadas entregavam a
  qualquer parceiro o dossiê do cliente de outro (endereço, telefone, nascimento, renda declarada,
  representante legal). Correção estrutural: `subject_profiles` passa a ter chave
  `(subject_id, tenant_id)` (migration V024, com backfill pelo primeiro tenant vinculado);
  `SubjectProfileService` não tem mais assinatura que aceite só o `subjectId`; a resposta do `PUT`
  deixou de carregar o cadastro. Fecha também o vetor de **escrita** (completar cadastro alheio para
  induzir aprovação automática), que já estava mapeado na Onda 2.
  *Verificado:* `TenantIsolationIntegrationTest` — cadastro completo de A deixa o checklist de B
  intacto, e a escrita de B não altera o de A.

- [x] **Match por nome do screening não encontrava quase ninguém** 🔴
  `FuzzyNameWatchlistProvider` usava Jaro-Winkler sobre a **string inteira** com limiar 0.95. O
  algoritmo premia prefixo igual e as listas de sanção publicam `SOBRENOME, Nome`: "JOSE ANTONIO DA
  SILVA" contra "SILVA, JOSE ANTONIO" — a mesma pessoa — ficava perto de 0.5. O controle rodava,
  registrava que rodou, e não achava ninguém. O comparador correto (`NameSimilarity`, token a
  token) já existia no repositório, usado pelos bureaus, e não estava ao alcance do screening.
  Extraído para `NameTokens` (commons) e usado nos dois sentidos; limiar agora é **por token**
  (0.90) e não é comparável ao 0.95 anterior.
  *Verificado:* `NameTokensTest` + `FuzzyNameWatchlistProviderTest` — ordem invertida, token a mais,
  token a menos e erro de digitação casam; primeiro nome igual e sobrenome diferente, não.

- [x] **Stub de CPF era fallback de bureau real indisponível** 🔴
  `StubBureauProvider` (`@Order(100)`) responde MATCH para qualquer CPF válido e é o último da
  cadeia — então um bureau real fora do ar convertia **indisponibilidade em identidade
  verificada**. O `CpfBureauReadinessGuard` não pegava: a configuração estava correta, o bureau
  *estava* habilitado, só não respondia. `BureauProvider.authoritative()` marca a diferença e
  `IdentityService` remove os não-autoritativos da cadeia quando existe um autoritativo para o
  tipo; o desfecho passa a ser `UNAVAILABLE` → REVIEW.
  *Verificado:* `IdentityServiceTest` — bureau real indisponível não cai para o stub; sem bureau
  real, o stub segue valendo (é o que sustenta dev/teste).

- [x] **`@Version` era decorativo; a lease expirada gerava duas decisões** 🔴
  `AssessmentRepositoryImpl.save` **relia** a entidade imediatamente antes de gravar, então o
  `@Version` era comparado consigo mesmo. O agregado, carregado minutos antes, não trazia versão.
  Com a lease expirada, duas réplicas concluíam a mesma avaliação, cada uma sobre sua cópia, ambas
  passando pelo guard de estado, ambas gravando evento com `eventId` distinto — a idempotência do
  webhook não filtra por isso, e o cliente recebia dois callbacks possivelmente contraditórios.
  Agora o agregado carrega a versão, a linha é lida com `FOR UPDATE` e o perdedor recebe
  `OptimisticLockingFailureException` (409 na API; descarte silencioso no processador).
  *Verificado:* `AssessmentProcessorTest` — perdedor da corrida não publica evento nem contabiliza
  tentativa.

- [x] **Nenhum client tinha connect timeout** 🔴
  Só o read timeout estava configurado, e no `HttpClient` da JDK o connect timeout é do cliente,
  não da requisição — por isso passou despercebido. Um TCP descartado por firewall travava a thread
  indefinidamente: o serviço parava de decidir sem exceção, sem retry, com health verde.
  `HttpWebhookClient` não tinha timeout algum e roda na thread do listener Kafka.
  Connect 2s + read configurável em todos; `max.block.ms`/`request.timeout.ms`/`delivery.timeout.ms`
  no producer.

- [x] **`TaskScheduler` com 1 thread** (item da Onda 1)
  `spring.task.scheduling.pool.size=4`. A importação das 03:00 não congela mais o processamento.

- [x] **A banda de score reprovava sozinha** 🔴 *(achado do teste com a API no ar)*
  `fromLevel(level)` entrava no `reduce` como valor inicial e disputava o `strongest` de igual para
  igual com as regras, então a banda podia agravar a decisão acima de tudo que qualquer regra
  pediu. Observado ao exercitar a API real: `PEP` (+300, pede REVIEW) somado a
  `SANCTION_NAME_MATCH` (+500, pede REVIEW) dá 800, cruza o limiar de 799 **por um ponto**, cai em
  CRITICAL e virava reprovação automática — duas exigências de julgamento humano produzindo uma
  recusa sem humano nenhum. Anulava o propósito do `MatchBasis` (homônimo não é reprovado sem
  revisão) e tratava PEP como impedimento, quando a Circular 3.978 pede diligência reforçada.
  Também somável: `SCREENING_COVERAGE` (+300), ou seja, o cliente podia ser recusado em parte
  porque *a nossa* importação de watchlist falhou.
  Agora a banda agrava até REVIEW e REJECT exige uma regra que o peça nominalmente — o que também
  garante que toda reprovação tenha, na trilha, um fator que a justifique pelo nome. Nenhuma recusa
  legítima se perde: `IDENTITY_NOT_FOUND` (900) e `SANCTION_HIT` por documento (1000) já ultrapassam
  799 sozinhas. O nível segue sendo reportado como CRITICAL; muda o que se faz com ele.
  *Verificado:* `RiskScoringServiceTest` (acúmulo de dois REVIEW → REVIEW; banda CRITICAL sem regra
  de recusa → REVIEW; banda HIGH ainda agrava APPROVE → REVIEW) e com a API no ar — o mesmo input
  que devolvia `REJECT · score 800` passou a devolver `REVIEW · score 800 · risco CRITICAL`,
  enquanto sanção por documento seguiu em `REJECT · score 1000`.

---

## 🚧 Onda 1 — Integridade da decisão (~30 dias)

Sem isto, nenhum controle acima é confiável: dá para contornar todos assumindo a identidade
de outro tenant.

- [x] **Autenticação por tenant (API key)** — `feat/tenant-api-key`
  Tenant derivado da credencial (`Authorization: Bearer brr_<keyId>_<secret>`); `X-Client-Id`
  deixou de ser lido. Segredo guardado só como hash; emissão por endpoint de admin.
  *Verificado:* `TenantIsolationIntegrationTest` — sem credencial, credencial forjada, `keyId`
  sem segredo e header `X-Client-Id` forjado respondem todos 401; credencial de tenant não abre
  endpoint administrativo e vice-versa.

- [ ] **Identidade do operador humano na revisão (EDD)**
  *Parcialmente endereçado:* a trilha agora grava `reviewed_by_key` — qual credencial decidiu,
  garantido pelo sistema e revogável — separado de `reviewed_by`, que continua sendo texto
  autodeclarado. Falta o que a API key não resolve: a credencial identifica o **sistema cliente**,
  não a pessoa. Identidade por operador exige autenticação de usuário (OIDC/SSO).
  *Pronto quando:* a decisão registra um operador autenticado, e não um texto livre.

- [ ] **`Idempotency-Key` no intake**
  Retry do cliente cria duas avaliações, dois custos de bureau, dois webhooks — e as decisões
  podem divergir, o que torna o retry um oráculo: tentar até o bureau falhar.
  *Pronto quando:* mesmo `POST` com a mesma chave em janela definida retorna a mesma avaliação.

- [x] **`FOR UPDATE SKIP LOCKED` + `@Version` nos dois pollers** — `fix/processing-integrity`
  Reivindicação com lease (`claimed_at`) nas avaliações, `SKIP LOCKED` na outbox, `@Version` no
  agregado. Antes, duas instâncias processavam a mesma avaliação e ambas emitiam evento com
  `eventId` diferente — a idempotência do webhook não pegava, e o cliente recebia dois callbacks
  possivelmente contraditórios.
  *Verificado:* `ConcurrentClaimIntegrationTest` contra Postgres real — reivindicações
  concorrentes devolvem conjuntos disjuntos; lease expirada devolve a avaliação à fila.

- [x] **Transação por avaliação; I/O externo fora da transação** — `fix/processing-integrity`
  Era `@Transactional` sobre lote de 50 com chamadas HTTP dentro: prendia conexão por minutos, e
  um bureau lento esgotava o pool derrubando a API inteira. Agora só a gravação do desfecho +
  evento é transacional.
  *Verificado:* `AssessmentProcessorTest` — falha de uma avaliação não impede as demais.

- [x] **Estado de falha + backoff no processamento** — `fix/processing-integrity`
  `attempts`/`last_error`/`next_attempt_at` com backoff exponencial e status
  `FALHA_PROCESSAMENTO` ao esgotar as tentativas. Antes, uma exceção virava poison pill infinita:
  a avaliação voltava ao topo da fila a cada 2s, sem limite e sem estado de erro, indistinguível
  de uma que ainda ia concluir.
  *Verificado:* `AssessmentProcessorTest` — tentativa contabilizada sem publicar evento; após N
  tentativas vai para `FALHA_PROCESSAMENTO`.
  ⚠️ **Falta o alerta por idade de `EM_ANALISE`** — sem ele a falha é registrada mas ninguém é
  avisado. Fica no item de observabilidade.

- [x] **`TaskScheduler` com mais de uma thread** — `fix/audit-top10`
  `spring.task.scheduling.pool.size=4`. Processador, relay e importação deixam de competir pela
  mesma thread. *Ainda não é um scheduler dedicado por job* — com pool compartilhado, quatro jobs
  lentos simultâneos ainda se bloqueiam; suficiente para os três jobs atuais.

- [ ] **Circuit breaker nos clients** — *timeouts fechados em `fix/audit-top10`*
  Connect (2s) + read timeout em todo client, e limites de tempo no producer Kafka. Falta o
  breaker: hoje um provider degradado continua sendo chamado a cada avaliação, cada uma pagando o
  timeout inteiro. Não há biblioteca de resiliência no classpath.
  *Pronto quando:* provider em falha abre o breaker e a avaliação vai direto para `UNAVAILABLE`.

- [x] **`OutboxRelay` publicava dentro da transação** 🔴 — `fix/audit-top10`
  `publishPending()` era `@Transactional` e chamava `kafkaTemplate.send(...).join()` **dentro**
  dela, segurando lock em até 100 linhas enquanto esperava o broker — o mesmo anti-padrão que o
  `AssessmentProcessor` documenta como causa de incidente e que tinha sido corrigido só lá. Agora
  segue a forma das avaliações: `claimed_at` com lease (V025), claim com `FOR UPDATE SKIP LOCKED`
  em transação curta, publicação **fora** de transação, marcação em transação própria. Falha libera
  a posse, para o ciclo seguinte tentar sem esperar a lease.
  *Verificado com o broker realmente parado:* as avaliações continuaram sendo decididas, a API
  respondeu em 27ms, `pg_stat_activity` não registrou **nenhuma** conexão `idle in transaction`
  (transação mais longa: `00:00:00`) e a outbox reteve os eventos como PENDING com as tentativas
  contando. Religado o Kafka, drenou em ~12s — 59 eventos, zero duplicados.

- [x] **Entrega de webhook sem posse** 🔴 *(achado novo, na revisão do webhook-api)*
  `findDue` varria as entregas vencidas e saía postando: sem lock, sem lease, sem `@Version`. Com
  réplicas, todas postavam a mesma entrega e o cliente recebia o veredito de KYC duplicado — a
  idempotência por `event_id` não cobre isso, ela impede duas *linhas*, não dois *POSTs* da mesma
  linha. E havia uma corrida **numa instância só**: `Delivery.create` nascia com
  `next_attempt_at = created_at` (já vencida), o listener gravava e só então postava (até 10s), e o
  scheduler — a cada 5s — encontrava a linha e postava em paralelo.
  Agora `claimDue` reivindica com lease em transação curta (V003, JPQL com `SKIP_LOCKED` por hint,
  porque native query não herda o `default_schema` do schema `webhook`), o POST fica fora da
  transação, e a entrega **nasce reivindicada** — quem cria é quem tenta em seguida.
  *Verificado:* `WebhookDeliveryServiceTest` (nasce reivindicada; falha libera a posse; `retryDue`
  reivindica antes de tentar) e `WebhookDeliveryIntegrationTest` contra Postgres real.

- [ ] **Endpoint de webhook por tenant**
  `barrier.webhook.target-url` é **global**: com dois tenants, um recebe as decisões de KYC do
  outro. Vazamento cross-tenant por desenho.
  *Pronto quando:* a entrega resolve a URL pelo `tenantId` do evento.

- [ ] **Não perder evento por falha transitória**
  `AssessmentCompletedListener` engole toda `RuntimeException` e commita o offset: banco fora do
  ar = decisão perdida para sempre, sem DLQ e sem reconciliação.
  *Pronto quando:* falha transitória não commita; existe DLQ e job que reconcilia
  `assessments` concluídos contra `deliveries`.

- [x] **Observabilidade mínima** — `fix/audit-top10`
  O MDC só existia na thread do servlet, então os logs da decisão não tinham `correlationId` nem
  `assessmentId` — ela roda num `@Scheduled`, minutos depois. Investigar uma aprovação indevida era
  `grep` em log de texto por documento mascarado.
  Agora a correlação é **persistida** (`assessments.correlation_id`, V027) e restaurada no
  processamento; propaga pela outbox (`outbox.correlation_id`) até o consumidor do webhook-api. Um
  único id liga `POST` → decisão → evento → callback, atravessando duas threads e um broker.
  Métricas de negócio via `micrometer-registry-prometheus` (`/actuator/prometheus`):
  `barrier.assessment.decisions{status,level}`, `barrier.assessment.processing` (timer),
  `barrier.assessment.pending.{count,oldest.seconds}`, `barrier.assessment.processing.failures`.
  **Sem PII em tag** — documento, nome e tenant ficam de fora, com teste que trava isso: métrica vai
  para um sistema sem o controle de acesso do banco e é retida indefinidamente.
  Log estruturado (ECS) no profile `prod`; padrão com correlação no console de dev.
  Regras de alerta em [docs/observability/alerts.yml](../observability/alerts.yml).
  *Verificado ao vivo:* `X-Correlation-Id` enviado pelo cliente aparece em todo o rastro assíncrono
  (bureau → screening → match fuzzy → decisão → publicação no Kafka), e as séries de negócio saem no
  `/actuator/prometheus`.
  ⚠️ Faltam as métricas de **cobertura de watchlist**, **outbox** e **entregas de webhook** — as três
  regras correspondentes no `alerts.yml` estão escritas e marcadas como dependentes delas.

- [ ] **`JSONB` nas colunas de evidência**
  `hits_json`, `results_json`, `factors`, `partners_json` são `VARCHAR(4000)`/`(2000)`: estouro
  vira exceção → poison pill, e **o que não cabe é justamente a evidência de auditoria**.
  *Pronto quando:* colunas migradas; QSA grande e múltiplos matches não quebram.

- [x] **Persistir também as regras que não dispararam** — `fix/audit-top10`
  `RiskScoringService` filtrava por `triggered()` antes de gravar, então "a regra de sanção não
  aparece na trilha" tinha três leituras indistinguíveis: rodou e o cliente estava limpo, estava
  desligada no registry, ou a lista estava vazia. Só a primeira é aceitável.
  `evaluated_json` (V028) guarda **todas** as regras do motor com o desfecho de cada uma —
  `TRIGGERED` / `NOT_TRIGGERED` / `SUPPRESSED`. `results_json` foi preservado na forma antiga de
  propósito: ele é lido para reconstruir decisões anteriores, e mudá-lo no lugar quebraria a
  leitura do histórico que este item existe para melhorar.
  Junto vieram mais duas lacunas de proveniência: `risk_scores` passou a referenciar o
  `identity_check_id` e o `screening_result_id` **exatos** que o alimentaram (uma avaliação
  retentada deixa várias linhas com o mesmo `assessment_id`, e nada dizia qual valeu), e
  `screening_results.sources_json` guarda fonte → versão da lista consultada (a base é substituída
  todo dia por `replaceSource`; sem o snapshot, um CLEAR de seis meses atrás é afirmação sem lastro).
  *Verificado ao vivo:* numa avaliação aprovada, as 9 regras aparecem com `NOT_TRIGGERED`, o
  snapshot traz `{"OFAC": "live-1", "SEED": "seed-v1"}`, e o `JOIN` de `risk_scores` com
  `identity_checks` e `screening_results` fecha nos ids gravados.
  *`ENGINE_VERSION` não subiu:* mudou o que é **registrado**, não o que é **decidido** — scores,
  bandas e recomendações são idênticos.

---

## 🚧 Onda 2 — Compliance e KYC de verdade (~60 dias)

- [ ] **Validar o CSV de PEP contra o portal real** ⚠️ *bloqueado por ambiente*
  A fonte foi escrita sem verificação: o ambiente de desenvolvimento recebe **403 inclusive para
  `ceis`**, caminho já validado. Rótulos são resolvidos por alternativas, mas isso é tolerância,
  não garantia.
  *Pronto quando:* uma importação real traz contagem plausível e os campos batem.

- [ ] **CSNU/ONU** — obrigação legal direta (Lei 13.810/19, indisponibilidade imediata de ativos).
  É a lista mais obrigatória de todas e é a que **não** existe.
  *Pronto quando:* nova `WatchlistSource` ativa e coberta pelo readiness guard.

- [ ] **Separar CEIS/CNEP de sanção financeira**
  Inidoneidade em licitação **não** impede relacionamento bancário, e hoje gera `REJECT`
  automático — negação de serviço a empresa legalmente apta.
  *Pronto quando:* `MatchType.DEBARMENT` com peso de alerta, não de bloqueio.

- [x] **Screening dos sócios PF e do representante legal** — `fix/audit-top10`
  O screening consultava só o titular: PJ com situação ATIVA, CNAE inócuo e um sócio na SDN saía
  aprovada automaticamente. `ScreeningCommand` passou a levar as partes relacionadas (QSA +
  representante legal, deduplicadas por nome normalizado), e todo apontamento carrega o
  `ScreenedParty` a que pertence — sem isso o analista recebe "sanção encontrada" sem saber se é a
  empresa ou um sócio, e as duas coisas exigem condutas diferentes.
  **Só o titular bloqueia:** apontamento de parte relacionada escala para revisão mas nunca reprova
  a PJ, porque a entidade sancionada é o sócio, não a empresa. Hoje o QSA vem sem documento, então
  na prática já seria match por nome; a trava existe para o dia em que um provedor de KYB trouxer o
  CPF do sócio.
  Custo: `WatchlistProvider.searchAll` carrega a base **uma vez** por avaliação em vez de uma por
  parte — sem isso uma PJ com 10 sócios faria 11 varreduras completas da tabela.
  *Verificado ao vivo, A/B no mesmo CNPJ:* sem representante legal declarado, nenhum apontamento
  (os 41 sócios reais do QSA não geraram falso positivo); com o representante legal declarado,
  `SANCTION_NAME_MATCH` atribuído a `REPRESENTANTE_LEGAL`, casando `"Jose Antonio da Silva"` com a
  entrada `"SILVA, JOSE ANTONIO"` a 100% — e desfecho `EM_REVISAO`, não reprovação.

- [x] **Evidência em `VARCHAR` estourava e derrubava a avaliação** 🔴 *(achado novo, ao vivo)*
  Estava mapeado como "perda de evidência de auditoria". É pior: o `INSERT` falha com *value too
  long*, a avaliação esgota as 5 tentativas e termina em `FALHA_PROCESSAMENTO`. Reproduzido com um
  CNPJ real — o Banco do Brasil tem **41 sócios** no QSA, 4577 bytes de JSON contra o teto de 4000.
  **Empresas grandes não conseguiam ser onboardadas**, e são justamente as de estrutura societária
  mais complexa. O mesmo teto valia para `hits_json` (cliente com muitos apontamentos, isto é, o de
  maior risco) e `results_json` (decisão com muitas regras disparadas): o limite estava exatamente
  onde a informação mais importa.
  `partners_json`, `hits_json` e `results_json` migrados para **JSONB** (V026) — sem teto e
  consultáveis, que é pergunta de auditoria ("quais avaliações tiveram apontamento de PEP?");
  `factors` virou `TEXT`, por ser texto legível e não JSON.
  ⚠️ `outbox.payload` e `deliveries.payload` seguem `VARCHAR(4000)`. Hoje o payload é pequeno e
  limitado (ids + status), mas é o mesmo modo de falha esperando um contrato de evento maior.

- [ ] **UBO ≥25%** — provedor KYB com percentual de participação; QSA da BrasilAPI não traz.
  *Pronto quando:* UBO indeterminado força REVIEW.

- [ ] **Rescreening / monitoramento contínuo**
  O motor roda **uma vez, no onboarding**. Cliente aprovado em janeiro e sancionado em março
  nunca é reavaliado. Exigência direta da Circular 3.978, e o gap mais silencioso de todos —
  não falha, simplesmente nunca acontece.
  *Pronto quando:* delta de cada importação dispara reavaliação dos subjects ativos.

- [ ] **Verificar dados, não só presença**
  `RegistrationCompleteness` checa se o campo está preenchido, não se é verdadeiro: preencher
  com dados plausíveis satisfaz o gate e libera aprovação automática.
  *Pronto quando:* OTP de telefone/e-mail, validação de endereço, nascimento contra bureau.

- [ ] **Proveniência por tenant no `SubjectProfile`**
  O cadastro é **global e gravável por qualquer tenant vinculado** — e o vínculo nasce de um
  simples `POST`. Um tenant pode completar o cadastro de um subject alheio e **induzir aprovação
  automática** em outro parceiro.
  *Pronto quando:* escrita é atribuída ao tenant; um tenant não altera o que outro declarou.

- [ ] **Histórico versionado de configuração** — *parcialmente fechado*
  Já resolvido: **quais regras estavam ativas** (`evaluated_json` distingue `SUPPRESSED` de
  `NOT_TRIGGERED`) e **contra qual lista** (`sources_json`).
  Aberto: `tenant_risk_config`, `risk_rule_registry` e `subject_profiles` seguem mutáveis sem
  histórico. O parâmetro efetivo aparece na evidência da regra que disparou (`config:months=`), mas
  não o de uma regra que passou — então "com que parâmetros" ainda não é respondível em geral.
  `risk_rule_registry` nem tem coluna `updated_by`, que `tenant_risk_config` tem.
  *Pronto quando:* uma decisão antiga é reproduzível a partir do que está gravado, incluindo os
  parâmetros efetivos de todas as regras.

- [ ] **Fila de EDD separada e 4-eyes**
  "Falta um campo cadastral" e "é PEP" caem na mesma fila, com a mesma severidade e o mesmo
  fluxo de decisão. Faltam `SOLICITAR_DOCUMENTO`, `BLOQUEIO_TEMPORARIO`, `ESCALADO_AML`.
  *Pronto quando:* PEP/mídia negativa exigem aprovação de dois revisores distintos.

- [ ] **Contract tests + golden dataset**
  Nenhum teste valida o schema de BrasilAPI/CGU/OFAC: mudança de coluna quebra em produção, em
  silêncio. E nada impede uma mudança de peso de inverter decisões em massa.
  *Pronto quando:* contract tests no CI e diff de decisões sobre dataset rotulado a cada mudança
  de regra.

---

## 🚧 Onda 3 — Escala, antifraude e dados (~90 dias)

- [ ] **Reescrever o match por nome** — `findNameEntries()` é `jpa.findAll()`: carrega a tabela
  **inteira** (~67k linhas com CGU+OFAC) como entidades JPA **a cada avaliação**. É o teto real
  de ~1–3 TPS e não é otimização prematura, é barreira.
  *Pronto quando:* p99 < 50ms e teste de carga sustenta a meta de TPS acordada.

- [ ] **Cache de registry e config por tenant** — hoje ~10 queries extras por avaliação.
  *Pronto quando:* TTL curto com invalidação no upsert.

- [ ] **Primeiros sinais de antifraude**, nesta ordem de custo/benefício:
  - [ ] velocity sobre `tenant_subjects` — **o dado já existe no banco e nenhuma regra o lê**;
        um CPF visto por 40 tenants em 24h não gera sinal algum hoje
  - [ ] colisão de endereço/telefone/e-mail entre subjects
  - [ ] coleta de device fingerprint e IP no intake (`SubmitAssessmentRequest` tem 3 campos)
  - [ ] reincidência de reprovação
  *Pronto quando:* cada sinal tem regra, teste adversarial e aparece na trilha.

- [ ] **Grafo de entidades** (pessoa × empresa × endereço × device × conta)
  Responde "quais entidades estão relacionadas a essa pessoa" e "já apareceu com outra
  identidade" — hoje ambas são irrespondíveis. `partners_json` guarda só o **nome** do sócio,
  sem documento: não é cruzável.

- [ ] **Criptografia em repouso + retenção**
  CPF/CNPJ em texto puro em 4 tabelas; sem KMS, sem tokenização, sem coluna de hash de busca.
  Sem política de expurgo, sem anonimização, sem fluxo de direitos do titular.
  *Pronto quando:* documento cifrado, busca por hash, retenção de 10 anos com expurgo.

- [ ] **Case management e COAF/SISCOAF**
  Um `POST /decision` não é case management: sem fila, SLA, atribuição, anexos, histórico.

- [ ] **Chaos e carga**
  Nenhum teste de banco fora, Kafka fora, provider lento ou retornando lixo. Nenhum teste de
  carga — por isso ninguém tinha percebido o `findAll()`.
  *Pronto quando:* SLIs/SLOs definidos (hoje **não há requisito não-funcional documentado**) e
  os cenários da auditoria §15 rodam no CI.

---

## 🔒 Bloqueado por decisão ou fornecedor

| Item | Bloqueio | Efeito hoje |
|---|---|---|
| Bureau real de CPF | Sem credenciais (BigBoost/Serpro). **Não existe API gratuita legítima** — o que se anuncia como tal é scraping com bypass de captcha ou base vazada | `CpfBureauReadinessGuard` impede prod; PF inviável |
| Validação do CSV de PEP | 403 do ambiente para o Portal da Transparência | Fonte escrita sem verificação |
| Provedor KYB (UBO) | Contrato | KYB só de 1º grau |
| Mídia negativa real | Contrato | `StubNegativeMediaProvider` com lista vazia |
| Volumetria/SLA alvo | Decisão de produto | "Escalável" não é afirmação verificável sem meta |

**Caminho sugerido para CPF:** gov.br Login Único (OIDC) devolve CPF e nome já verificados
(selo prata/ouro implica validação biométrica com o TSE) a custo baixo — exige credenciamento,
cujas condições atuais para empresa privada precisam ser confirmadas.

---

## Convenções

- Mudança de regra ou peso **sobe `ENGINE_VERSION`** (atual: `barrier-risk-rules/1.4.0`).
- Bug corrigido vem com teste de regressão que **falha antes** da correção.
- Controle novo que possa faltar em produção ganha um `ReadinessGuard` no padrão dos existentes:
  falha a subida em `prod`, avisa nos demais profiles.
- Ao fechar um item, atualize a tabela **Onde estamos** e mova para ✅ com o commit.
