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
| Escalabilidade | 2,0 | 2,0 | Intocado. **Agora medido:** ingestão 292 req/s, processamento ~12,5/s (bureau simulado) — e sem cota nem isolamento por tenant ([ADR-0015](../adr/0015-ingestao-em-massa-faixa-separada.md)) |
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

- [x] **`Idempotency-Key` no intake** — `fix/audit-top10`
  Retry do cliente criava duas avaliações, dois custos de bureau, dois webhooks — e as decisões
  podiam divergir, o que tornava o retry um oráculo: tentar até o bureau falhar.
  Header opcional; a chave é escopada por tenant (`idempotency_keys`, V029) e vale por uma janela
  configurável (`barrier.assessment.idempotency-window`, 24h). Repetição com o mesmo conteúdo
  devolve a avaliação original com `Idempotency-Replayed: true`; **mesma chave com conteúdo
  diferente responde 409**, porque servir a resposta antiga para outra requisição seria mentir.
  A comparação é por hash SHA-256 do conteúdo — a tabela não guarda CPF nem nome de novo.
  A reserva é gravada em transação própria **antes** da avaliação: é o índice único que serializa
  duas requisições concorrentes com a mesma chave (verificar com um `SELECT` antes deixaria
  justamente a janela em que nascem as duas avaliações). Enquanto a submissão original não terminou,
  a chave não tem avaliação para devolver e a concorrente recebe 409 em vez de resposta parcial;
  submissão que falha libera a chave, para o retry legítimo não esbarrar em 409 até o fim da janela.
  Documento é normalizado antes da reserva — requisição inválida responde 400 sem queimar a chave.
  *Verificado:* `IdempotentIntakeIntegrationTest` contra Postgres real — repetição devolve o mesmo
  id com uma única linha em `assessments`; `111.444.777-35` e `11144477735` são a mesma requisição;
  conteúdo diferente dá 409 e não cria nada; chave igual de outro tenant não colide; sem header, o
  comportamento anterior (uma avaliação por POST) é preservado. E `AssessmentServiceTest` — falha
  depois da reserva libera a chave e não faz o bind.

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

- [x] **Circuit breaker nos clients** — `fix/audit-top10`
  Connect (2s) + read timeout em todo client e limites de tempo no producer Kafka já estavam
  fechados; faltava o breaker. Um provider degradado seguia sendo chamado a cada avaliação, e
  **cada uma pagava o timeout inteiro** — indisponibilidade de terceiro virava lentidão do próprio
  serviço, que é pior que a falha, porque segura threads que teriam outra coisa a fazer.
  `CircuitBreaker` (CLOSED/OPEN/HALF_OPEN) com limite de falhas **seguidas** e período de abertura
  configuráveis (`barrier.resilience.failure-threshold`=5, `open-duration`=PT30S); um por provider,
  via `CircuitBreakerRegistry`. `IdentityService` consulta antes de sair para a rede: disjuntor
  aberto conta como indisponibilidade daquele bureau, a cadeia segue para o próximo, e sem próximo
  o desfecho é `UNAVAILABLE` — que a `IdentityRiskRule` já converte em revisão humana.
  Meia-abertura libera **uma** sondagem: sem isso, no instante em que o período vence, todas as
  avaliações represadas partem juntas para um provider que talvez ainda esteja doente.
  Só `BureauUnavailableException` conta para o disjuntor — um erro de programação (NPE, parsing)
  não é provider fora do ar, e abrir por causa dele esconderia o bug atrás de um `UNAVAILABLE`.
  *Escrito à mão, sem biblioteca de resiliência:* é o único uso, cabe numa classe testável, e a
  dependência traria autoconfiguração para um problema de três estados e um contador. Estado por
  instância — cada réplica aprende com as próprias chamadas; compartilhar exigiria coordenação no
  caminho da decisão para ganhar poucas chamadas.
  *Verificado:* `CircuitBreakerTest` (abre no limite; sucesso no meio zera o contador; meia-abertura
  libera só uma; sondagem boa fecha, sondagem ruim reabre) e `IdentityServiceTest` — depois de 3
  falhas o bureau **deixa de ser chamado** (`verify(times(3))` não sobe) e a avaliação vai para
  `UNAVAILABLE`; com disjuntor aberto no primário, o secundário saudável ainda atende.
  ⚠️ Cobre a cadeia de bureaus. Watchlist (job agendado, fora do caminho da decisão) e a entrega de
  webhook (que já tem backoff próprio e fila) continuam sem breaker — de propósito.

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

- [x] **Endpoint de webhook por tenant** 🔴 — `fix/audit-top10`
  `barrier.webhook.target-url` era **global**: com dois tenants, um recebia as decisões de KYC do
  outro. Vazamento cross-tenant por desenho, e o dado vazado é o pior possível — documento, nome e
  veredito de PLD-FT de clientes de outra empresa.
  Agora a entrega resolve a URL pelo `tenantId` **do evento** (`webhook_endpoints`, V004 do schema
  `webhook`). Sem registro, a entrega **não acontece** e fica logada — não entregar é reversível,
  entregar no lugar errado não é; a decisão continua disponível no `GET /v1/assessments/{id}`.
  O destino global sobrevive só como conveniência de dev: em `prod` a aplicação não sobe com ele
  definido (`GlobalTargetUrlReadinessGuard`). Endpoint desativado também não cai no global — cair
  reintroduziria o vazamento.
  Registro por `PUT/GET/DELETE /v1/webhook-endpoints/{tenantId}`, protegido por `X-Admin-Key`
  (mesma trava da risk-engine, portada): deixar o parceiro apontar o próprio destino seria
  self-service para redirecionar callback alheio. `DELETE` desativa, não apaga — o registro é o que
  se consulta quando um cliente reclama de callback não recebido. A URL é validada no domínio:
  http(s) absoluto, e **TLS obrigatório** fora de host local (a assinatura HMAC prova origem, não
  confidencialidade).
  *Verificado:* `WebhookDeliveryIntegrationTest` contra Postgres real — com a 'acme' registrada, o
  callback dela chega no servidor dela e **não** no destino global; `WebhookEndpointApiIntegrationTest`
  (401 sem `X-Admin-Key` e com chave errada, 400 para URL sem TLS, desativação preserva o cadastro,
  404 para tenant desconhecido); `WebhookDeliveryServiceTest` (endpoint do tenant vence o global;
  desativado não entrega; evento sem tenant não entrega).

- [x] **Segredo HMAC por tenant** *(achado ao endereçar o item acima)* — `fix/audit-top10`
  `barrier.webhook.secret` era um só para todos: quem conhecesse o segredo — um parceiro, um
  ex-integrador, um vazamento de config — forjava um callback de KYC válido para **qualquer** outro
  tenant, inclusive um "APROVADO". O endpoint por parceiro resolveu para onde o resultado vai; isto
  resolve quem consegue provar que ele veio do Barrier.
  Segredo próprio por registro (`webhook_endpoints.secret`, V005), gerado com `SecureRandom` (32
  bytes) e devolvido **uma única vez**, no registro e na rotação — o `GET`/lista nunca o expõem,
  só `secretConfigured`. Mesmo desenho da emissão de API key.
  Rotação sem downtime por `POST /v1/webhook-endpoints/{tenantId}/rotate-secret`: o anterior segue
  aceito por `secret-rotation-overlap` (24h) e, durante a janela, cada entrega leva **duas**
  assinaturas — a nova em `X-Barrier-Signature` e a anterior em `X-Barrier-Signature-Previous`.
  Header novo em vez de mudar o formato do principal: cliente que já verifica não precisa saber que
  existe rotação. Sem a janela, rotacionar seria escolher entre reusar segredo comprometido e
  derrubar a verificação do parceiro — e na prática ninguém rotacionaria.
  Atualizar a URL de um tenant já registrado **preserva** o segredo: trocá-lo de quebra derrubaria a
  verificação sem ninguém ter pedido. O segredo é resolvido a cada tentativa, então uma rotação
  entre a primeira tentativa e o retry assina com o que vale agora.
  *Verificado:* `WebhookDeliveryServiceTest` — assina com o segredo do tenant e não com o global;
  durante a rotação vão as duas assinaturas; vencida a janela, a anterior some.
  `WebhookEndpointApiIntegrationTest` — o `GET` e a lista não contêm o segredo devolvido no
  registro; atualizar a URL preserva; rotação troca e abre a janela; rotação sem `X-Admin-Key` dá
  401 e de tenant desconhecido dá 404. `WebhookEndpointTest` cobre o domínio.
  ⚠️ O segredo fica **em texto** na coluna — assinar exige o valor (diferente das API keys, que são
  hash). Criptografia em repouso é item da Fase 6 e vale para esta coluna.

- [x] **Não perder evento por falha transitória** — `fix/audit-top10`
  `AssessmentCompletedListener` engolia toda `RuntimeException` e retornava normalmente, o que
  commitava o offset: banco fora do ar por trinta segundos = toda decisão daquele intervalo perdida
  em definitivo, sem entrega e sem rastro além de uma linha de log. O motivo original de engolir era
  legítimo — mensagem malformada retentada trava a partição —, mas a cura valia para as duas falhas
  e só uma delas merecia.
  Agora a distinção é explícita: o que não tem conserto vira `MalformedEventException` e vai direto
  para a DLT (`addNotRetryableExceptions`); o resto **sobe**, e o `DefaultErrorHandler` retenta com
  backoff exponencial **sem commitar** — se a instância morrer no meio, outra retoma do mesmo ponto.
  Esgotado o `retry-max-elapsed` (2 min), o evento também vai para `<tópico>.DLT`: ficar preso na
  partição pararia a entrega de **todos** os tenants.
  A DLT sozinha não fecha o buraco (o que está lá é decisão que o cliente não recebeu, e ninguém
  volta lá), então entra o `DeliveryReconciliationJob`: a cada 15 min relê o tópico numa janela de
  6h com um consumidor **avulso** (`assign`, sem group management e sem commit — não mexe no offset
  do consumidor normal) e cria a entrega de todo evento sem uma. A fonte de verdade é o tópico e
  não uma consulta às `assessments`: elas vivem no schema de outro serviço, e ler de lá trocaria
  uma lacuna de entrega por acoplamento entre schemas. **O limite é a retenção do Kafka** — a janela
  precisa caber dentro dela, e é ela que define quanto de indisponibilidade dá para recuperar.
  *Verificado:* `DeliveryReconciliationIntegrationTest` contra Kafka e Postgres reais, com o
  listener desligado (`auto-startup=false`) para reproduzir o consumidor fora do ar — duas decisões
  publicadas e não consumidas viram duas entregas, e a segunda passada da reconciliação recupera
  zero (não duplica o veredito). `AssessmentCompletedListenerTest` — falha transitória **sobe** em
  vez de ser engolida; JSON inválido e payload ilegível viram `MalformedEventException`; evento sem
  `tenantId` segue (quem decide é a resolução de destino).

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

- [x] **CNPJ fica sem bureau real em produção** 🔴 — `fix/audit-top10`
  Resolvido pela rota (a) do que estava mapeado abaixo, que é a que segue o ADR-0014: existe
  `BigBoostCnpjBureauProvider` (dataset `basic_data` da API de Empresas), `@Order(20)`, na mesma
  flag e credenciais do bureau de CPF. Com a BrasilAPI fora do ar, a cadeia de PJ agora cai em
  **outro bureau real**; sem nenhum, o desfecho é indisponibilidade → revisão humana, e não
  verificação fictícia.
  `CnpjBureauReadinessGuard` no padrão do de CPF: em `prod`, sem provider autoritativo de CNPJ a
  aplicação **não sobe**, e bureau contratado apontando para endereço local também barra (simulador
  com crachá desarmaria a primeira checagem). A BrasilAPI virou desligável
  (`barrier.identity.brasilapi.enabled`) e `application-prod.yml` liga a BigBoost por padrão.
  A rota (b) continua possível e **fica registrada quando escolhida**: com a BrasilAPI como único
  bureau de PJ em produção, a aplicação sobe mas o guard emite aviso nomeando a dependência — API
  pública gratuita, sem SLA e sem contrato sustentando um controle regulatório.
  *Verificado:* `BigBoostCnpjBureauProviderTest` (13 casos — ativa+nome casa; nome fantasia vale;
  nome divergente é MISMATCH; BAIXADA/SUSPENSA/INAPTA → MISMATCH, NULA → NOT_FOUND, **situação
  ausente ou desconhecida → MISMATCH e nunca MATCH**; 5xx vira indisponibilidade; data ilegível não
  quebra) e `CnpjBureauReadinessGuardTest`.
  ⚠️ Dois limites: o schema do dataset de empresas **não foi verificado contra a API real** (mesma
  situação do dataset de pessoas — depende de credencial contratada), e o `basic_data` **não traz
  QSA**, então uma avaliação atendida por este provider entrega perfil com abertura e CNAE mas sem
  sócios, e a regra de estrutura societária (KYB 1º grau) fica sem entrada. Fechar isso exige o
  dataset de relacionamentos — vira item do UBO, abaixo.


- [x] **CSNU/ONU** — obrigação legal direta (Lei 13.810/19, indisponibilidade imediata de ativos)
  — `fix/audit-top10`
  Era a lista mais obrigatória de todas e a única que **não** existia: o motor decidia PLD-FT sem
  nunca consultá-la. `UnWatchlistSource` ingere a lista consolidada (XML único, pessoas em
  `INDIVIDUALS` e entidades em `ENTITIES`), declara `provides() = SANCTION` — então entra
  automaticamente na cobertura exigida pelo `WatchlistReadinessGuard` — e está **ligada por padrão
  no profile `prod`**, porque não é decisão de apetite.
  O nome vem quebrado em até quatro campos e é remontado; **cada alias vira entrada própria**, como
  os `aka` da OFAC: a ONU publica grafias alternativas porque transliteração do árabe/cirílico
  varia, e casar só o nome principal perde o apontamento por diferença de grafia.
  Sem documento: o CSNU identifica por nome, nacionalidade e data de nascimento, e não publica
  CPF/CNPJ. O casamento é sempre por nome, ou seja, sempre `MatchBasis.NAME` — pontua alto e escala
  para revisão humana, sem reprovar sozinho, que é o comportamento já existente da
  `SanctionRiskRule`.
  *Verificado:* `UnWatchlistSourceTest` — nome remontado dos quatro campos, alias virando entrada,
  alias vazio ignorado, ausência de documento, classificação como `SANCTION`, e o detalhe trazendo
  referência (`TAi.004`) e regime para o analista achar a entrada na fonte. Inclui **recusa de XML
  com DOCTYPE**: lista de terceiro é entrada não confiável, e com entidades externas habilitadas um
  arquivo publicado ou interceptado leria arquivo local ou faria o serviço bater em endereço interno
  (XXE).
  ⚠️ Como CEIS/CNEP saíram de `SANCTION` (item acima), a cobertura de sanção em produção agora vem
  de **OFAC + CSNU** — as duas precisam estar habilitadas.

- [ ] **Sem limite de vazão na entrega de webhook** *(achado ao rodar carga)*
  Observado com k6: o intake sustenta o ramp, mas cada avaliação concluída vira uma entrega, e o
  retry com backoff não tem teto **por tenant**. Com 1004 avaliações na fila e 2800 entregas
  falhadas, o serviço passou a metralhar o endpoint do cliente — 1818 vencidas simultaneamente,
  reivindicadas de 100 em 100 a cada 5s. Um parceiro com endpoint lento ou fora do ar vira alvo do
  próprio backlog: o retry que existe para ajudá-lo é o que o derruba.
  Não é o problema de escala da Onda 3 (aquele é o custo por avaliação); é ausência de controle de
  vazão na saída.
  *Pronto quando:* existe teto de entregas por minuto por tenant, e o backoff é global por endpoint
  e não por linha.

- [x] **Separar CEIS/CNEP de sanção financeira** — `fix/audit-top10`
  Inidoneidade em licitação **não** impede relacionamento bancário, e gerava `REJECT` automático —
  negação de serviço a empresa legalmente apta. `MatchType.DEBARMENT` é categoria própria;
  `CeisWatchlistSource`/`CnepWatchlistSource` passaram a produzi-la, `DebarmentMatchRule` a
  transforma em apontamento e `DebarmentRiskRule` dá **peso de alerta**: 200 + REVIEW para match por
  documento no titular (CNPJ exato, sem ambiguidade — o analista decide com o contexto do negócio),
  100 e sem recomendação para match por nome. Nunca recusa automática, e nunca escala por
  apontamento de sócio: a entidade punida é ele, não a empresa avaliada.
  O apontamento **continua na trilha e continua pesando** — é informação reputacional legítima para
  PLD-FT e pode levar à revisão pela banda de score somado a outros fatores. O que deixou de existir
  é o caminho direto para a recusa.
  `DEBARMENT` **não** entra em `RegulatoryRiskRules`, de propósito: nenhuma norma do Bacen manda
  recusar conta por inidoneidade em licitação, então é regra de apetite de risco e pode ser
  desligada pelo registry como qualquer outra (V030).
  ⚠️ **Consequência operacional:** a CGU deixa de contar como cobertura de `SANCTION`. A única fonte
  de sanção financeira passa a ser a OFAC — habilitar só a CGU em `prod` agora falha no
  `WatchlistReadinessGuard`, o que é a verdade que antes ficava escondida.
  `ENGINE_VERSION` → `1.6.0` (mudou o que é decidido, não só o que é registrado).
  *Verificado:* `DebarmentRiskRuleTest` (documento no titular → REVIEW e nunca REJECT; nome → só
  pontua; sócio não escala; evidência identifica parte e fonte) e `ScreeningRulesTest` — CEIS e OFAC
  no mesmo screening produzem apontamentos de categorias diferentes.

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

- [x] **Rescreening / monitoramento contínuo** 🔴
  O motor rodava **uma vez, no onboarding**: cliente aprovado em janeiro e sancionado em março
  seguia aprovado para sempre. Não falhava — simplesmente nunca acontecia, que é por que não
  aparecia em log nem em métrica.
  O gatilho é o **delta** de cada importação (`WatchlistDelta`, calculado dentro do
  `replaceSource` — único ponto onde as duas versões da lista coexistem, já que a base é
  substituída inteira). Quem casa com uma entrada nova é reavaliado: **por documento** (CEIS/CNEP
  e parte da OFAC) e **por nome** (OFAC e CSNU não publicam documento — sem esse caminho o
  monitoramento cobriria só inidoneidade e ignoraria sanção financeira, que é a obrigação legal).
  O limiar e o piso de tamanho de nome são os mesmos do screening da avaliação: divergir produziria
  cliente que o monitoramento levanta e a avaliação resultante não confirma.
  Reavaliar é **submeter uma avaliação nova pelo mesmo pipeline** (`AssessmentService.submit`, com
  `origin = RESCREENING` e `origin_detail = fonte@versão`, migration V032). Um caminho paralelo que
  só refizesse o screening decidiria diferente do onboarding sobre o mesmo cliente e não produziria
  decisão, evento nem webhook — o parceiro fica sabendo pelo canal de sempre. Sem
  `Idempotency-Key`: reaproveitar a decisão anterior devolveria exatamente o resultado tomado antes
  de o cliente estar na lista.
  Três travas contra a avalanche: **linha de base** (importação sobre fonte vazia não dispara nada
  — senão subir o sistema ou ligar uma fonte nova reavaliaria toda a base), **teto por importação**
  (acima dele aborta e grita, porque delta gigante é fonte que mudou de layout e cada reavaliação
  custa uma consulta de bureau paga) e **uma avaliação por (subject, tenant) por importação**.
  Reavaliação é por tenant, não por subject: o subject é global, a decisão não.
  *Verificado:* `RescreeningServiceTest` (9 casos — documento; nome invertido `SILVA, JOSE ANTONIO`
  casando com `Jose Antonio da Silva`; homônimo parcial que não casa; um por tenant vinculado;
  cliente afetado por duas entradas gerando uma avaliação só; linha de base; teto; falha em um
  cliente não interrompe os demais; desligado não toca na base) e `WatchlistImporterTest` (delta
  repassado com fonte e versão; falha do rescreening não invalida a importação).
  ⚠️ Custo: o match por nome é entradas novas × clientes, uma vez por importação. A base de
  clientes é percorrida em páginas, mas continua sendo varredura sem índice — é o mesmo problema
  do `findNameEntries()` listado na Onda 3, e escala junto com ele.
  ⚠️ Cobre quem **entra** na lista. Quem sai dela não dispara nada, e reavaliação periódica de
  quem nunca casou com nada (revisão cadastral por prazo) continua aberta.

- [ ] **Verificar dados, não só presença**
  `RegistrationCompleteness` checa se o campo está preenchido, não se é verdadeiro: preencher
  com dados plausíveis satisfaz o gate e libera aprovação automática.
  *Pronto quando:* OTP de telefone/e-mail, validação de endereço, nascimento contra bureau.

- [x] **Proveniência por tenant no `SubjectProfile`** 🔴 — `fix/audit-top10`
  O cadastro era **global e gravável por qualquer tenant vinculado**, e o vínculo nasce de um
  simples `POST /v1/assessments`. Isso furava nas duas direções:
  **escrita** — um tenant completava o cadastro de um subject alheio e satisfazia o gate de
  `RegistrationCompleteness` de outro parceiro, induzindo aprovação automática numa avaliação que
  deveria cair em revisão; **leitura** — o `PUT .../profile` devolvia o cadastro depois do merge e
  patch vazio não altera nada, então duas chamadas (POST para criar o vínculo, `PUT {}` para ler)
  entregavam endereço, telefone, e-mail, nascimento, renda declarada e representante legal do
  cliente de outro parceiro.
  A chave virou `(subject_id, tenant_id)` (V024, com backfill atribuindo cada perfil ao tenant que
  primeiro se vinculou ao subject). O `Subject` continua global — é o que sustenta a deduplicação
  por documento; o que deixou de ser compartilhado é o dossiê. `SubjectProfileRepository` e
  `SubjectProfileService` **não têm assinatura que aceite só o `subjectId`**: o tipo do método é a
  defesa, um endpoint novo não tem como esquecer de passar o tenant. O enriquecimento pelo bureau
  (`persistPersonProfile`/`persistCompanyProfile`) grava sob o tenant da avaliação, e o
  `ProfileResponse` passou a devolver só completude, não o cadastro.
  *Verificado:* `TenantIsolationIntegrationTest` — `cadastroDeclaradoPorUmTenantNaoVazaParaOutro`
  (a asserção é sobre a completude do tenant B, não sobre o formato da resposta: se B enxergasse o
  cadastro de A, o checklist de B viria completo sem B ter declarado nada) e
  `escritaDeUmTenantNaoAlteraOCadastroDoOutro`.
  ⚠️ Consequência: o mesmo dado pessoal passa a existir uma vez por tenant declarante. Retenção e
  criptografia em repouso (Fase 6) valem por linha. E o cadastro segue **sem histórico** — quem
  mudou o quê e quando é o item de histórico versionado, logo abaixo.

- [x] **Histórico versionado de configuração**
  Já vinha resolvido: **quais regras estavam ativas** (`evaluated_json` distingue `SUPPRESSED` de
  `NOT_TRIGGERED`) e **contra qual lista** (`sources_json`). Faltavam as duas metades abaixo, que
  são complementares — uma responde sobre a decisão, a outra sobre a configuração.
  **1. Parâmetro efetivo de toda regra que rodou**, inclusive das que passaram:
  `RiskRule.effectiveParameters(context)` (default vazio — regra sem configuração não polui a
  trilha) entra no `evaluated_json` junto do desfecho. Antes, o valor só aparecia na evidência da
  regra que *disparou*; uma regra que passou não deixava rastro do valor usado, e como
  `tenant_risk_config` é sobrescrito no lugar, "por que a regra de empresa nova não pegou este
  cliente em março?" era respondido com a janela de hoje, que pode nunca ter valido em março.
  Implementado em `NewCompanyRiskRule` (`months`/`score`) e `SensitiveCnaeRiskRule` (`cnae-codes`
  como lista ordenada, `score`) — as duas configuráveis por tenant.
  **2. Linha do tempo da configuração** (V033): `tenant_risk_config_history` e
  `risk_rule_registry_history`, append-only, escritas pelo mesmo repositório que faz a alteração e
  **na mesma transação** — histórico gravado à parte falta exatamente quando a mudança aconteceu.
  Sem trigger de banco de propósito: esconderia a escrita de quem lê o serviço. `param_value` nulo
  no histórico significa "voltou ao default global", que é uma mudança de controle como outra
  qualquer. O caso que mais precisava disso é o kill switch — regra desligada por uma semana e
  religada não deixava nenhum vestígio da semana em que não rodou.
  **3. `updated_by` no registry**, que `tenant_risk_config` já tinha e era obrigatório lá:
  desligar uma regra de risco é a operação mais sensível do sistema e era a única sem autoria. O
  `X-Admin-Key` prova que quem chamou tinha a chave, não quem decidiu. Agora é exigido no `PUT`.
  *Verificado:* `ConfigHistoryIntegrationTest` (Postgres real — o que se testa aqui é o SQL, e um
  mock confirmaria a chamada, não a linha: duas alterações de override viram duas entradas com
  autores distintos e a tabela viva mantém o valor corrente; desligar e religar deixa os dois
  estados), `RiskScoringServiceTest` (parâmetro efetivo presente em regra que passou; regra sem
  configuração não registra nada) e `RiskRuleRegistryServiceImplTest` (registry exige autoria).
  ⚠️ Fica aberto o histórico de `subject_profiles`: é dado pessoal, e versionar cadastro multiplica
  o volume sujeito a retenção de 10 anos e criptografia em repouso (Fase 6) — decidir junto com
  aquele item, não antes.

- [ ] **Fila de EDD separada e 4-eyes** — *parcialmente fechado*
  Fechado: **`SOLICITAR_DOCUMENTO`** (`fix/audit-top10`). "Falta um campo cadastral" e "é PEP"
  caíam na mesma fila, com a mesma severidade — e o volume que o time de operações mais via era
  justamente o que menos precisava dele: numa base de teste, **7501 de 7529** avaliações em
  `EM_REVISAO` eram `APPROVE · score 0` rebaixadas por cadastro incompleto. Agora risco aprovado +
  cadastro incompleto vira status próprio, fora da fila de análise.
  **Não virou reprovação**, e a escolha é deliberada: reprovar por falta de dado mentiria na trilha
  (a recusa não teria fator de risco que a justificasse pelo nome — a mesma regra que a correção da
  banda de score estabeleceu), contaminaria a taxa de recusa que o regulador lê como indicador de
  PLD-FT, e seria terminal. Sai do estado completando o cadastro e submetendo nova avaliação;
  `Assessment.decide` continua exigindo `EM_REVISAO`, para ninguém "aprovar" cadastro que segue
  incompleto.
  Junto veio a redução do volume na origem: o bureau agora **preenche** o cadastro de PF (ver
  `PersonProfile`), então nascimento, nacionalidade e endereço deixam de ser cobrados do parceiro.
  Sobra ocupação, que bureau nenhum fornece.
  *Verificado:* `AssessmentProcessorTest` — aprovado com cadastro incompleto vai para
  `SOLICITAR_DOCUMENTO` e não para `EM_REVISAO`/`REPROVADO`; o que já era revisão por risco continua
  em revisão.
  Aberto: `BLOQUEIO_TEMPORARIO`, `ESCALADO_AML` e o 4-eyes.
  *Pronto quando:* PEP/mídia negativa exigem aprovação de dois revisores distintos.

- [x] **Cadastro de PF vinha do bureau e era descartado** — `fix/audit-top10`
  `BureauResult` só carregava `CompanyProfile`: os dados objetivos de PJ eram persistidos no
  cadastro, os de PF não tinham por onde entrar. O efeito era que **toda** avaliação de pessoa
  física era rebaixada por cadastro incompleto, mesmo com o bureau tendo respondido — e nem o
  bureau real resolveria, porque o `BigBoostBureauProvider` mapeava só nome e situação cadastral.
  `PersonProfile` fecha a simetria (nascimento, nacionalidade, endereço), persistido por patch —
  campo que o bureau não trouxe preserva o que o parceiro declarou. O bureau simulado devolve o
  perfil completo, e ganhou o cenário `9998…` = "responde sem dados cadastrais", para o gate da
  CMN 4.753 continuar exercitável em dev: mock que sempre completa o cadastro esconderia o
  rebaixamento, que é o mesmo erro do stub que aprovava tudo.
  *Verificado:* `FakeCpfBureauPersonProfileTest` — o perfil do bureau sozinho deixa faltando
  **exatamente** `ocupação`, e com ela declarada o cadastro fecha.

- [x] **Consulta ao bureau não deixava rastro verificável** — `fix/audit-top10`
  `identity_checks` guardava provider, status, detail e instante — tudo afirmação nossa sobre nós
  mesmos. Não dava para conferir contra o extrato do provedor numa inspeção, reconciliar a fatura
  (cada consulta é paga) nem investigar uma contestação sem refazer a consulta, que hoje
  responderia outra coisa. É a mesma lacuna que `sources_json` fechou para as listas, deixada
  aberta só no bureau.
  `provider_reference` (o `QueryId` da BigDataCorp) e `raw_response` em JSONB (V031). O payload vai
  **com redação** dos campos que o projeto já decidiu não guardar — nome da mãe é fator de
  autenticação, e a decisão de guardar só o resultado da comparação estava documentada no DTO desde
  que ele existe; gravar o bruto a desfaria em silêncio. O `QueryId` é o ponteiro para a cópia
  íntegra que o provedor mantém sob o controle de acesso dele.
  Fonte sem identificador de consulta (BrasilAPI) fica com `provider_reference` nulo — registrar a
  ausência é mais honesto que inventar um id nosso.
  *Verificado:* `BureauTraceTest` — extrai o id, redige o nome da mãe preservando o que sustentou a
  decisão, guarda só o ponteiro com a persistência desligada, e corpo ilegível não derruba a
  verificação (rastro é evidência, não decisão).
  ⚠️ Isto passa a guardar dado pessoal por avaliação: **retenção de 10 anos e criptografia em
  repouso (Fase 6) valem explicitamente para `raw_response`**. Até lá é desligável em
  `barrier.identity.store-raw-response`.

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

- [ ] **Ingestão em massa não tem cota nem isolamento** 🔴 — ver
  [ADR-0015](../adr/0015-ingestao-em-massa-faixa-separada.md)
  Medido em teste de carga (k6, ramp até 150 VUs): a ingestão aceita **292 req/s com 0% de erro**
  enquanto o processamento conclui **~12,5/s** (bureau simulado; ~3/s com bureau real). Em 5 min
  entraram 70.558 avaliações e saíram ~800 — **69.809 presas em `EM_ANALISE`**, ~92 min só para
  drenar. Duas consequências que não são "lentidão":
  - **Sem isolamento entre tenants:** `SELECT_CLAIMABLE` ordena por `created_at` global, sem
    `tenant_id`. Um cliente tombando 500 mil registros coloca o tempo real de todos os outros
    atrás da fila dele — dois dias — sem violar nenhuma regra da API.
  - **Sem autorização de custo:** a R$0,04/consulta da BigBoost, 500 mil = **R$20 mil**, 1 milhão
    = **R$40 mil**, incorridos por um laço sobre o `POST` que ninguém aprovou. Acelerar o
    processamento antes de controlar a entrada só queima o mesmo dinheiro mais rápido.
  *Pronto quando:* lote grande exige cota configurada por tenant (fail-closed sem ela), faixa
  `BULK` só consome capacidade ociosa, e um teste de concorrência prova que backfill de um tenant
  não atrasa o tempo real de outro.

- [ ] **Paralelizar o processamento** — depois da cota e do cap de bureau, nesta ordem
  `AssessmentProcessor.process()` reivindica `BATCH = 50` e processa **sequencialmente numa única
  thread**; o `pool.size: 4` separa processador, relay e importador, mas não paraleliza o
  processamento em si. Também não há config de Hikari em nenhum `application.yml` — vale o default
  de 10 conexões, compartilhado com o Tomcat, então passar de ~8 workers sem dimensionar o pool
  troca espera por bureau por espera por conexão. Paralelismo **fixo e configurável**
  (`barrier.assessment.workers`), não autoscaling por profundidade de fila — o racional está na
  ADR-0015. `SKIP LOCKED` + lease já tornam isso seguro sem locking novo.
  *Pronto quando:* workers configuráveis, pool dimensionado junto, semáforo de concorrência por
  bureau com tratamento de `429`, e teste de concorrência sem processamento duplicado.

- [ ] **Métrica de idade da fila**
  Nada mede há quanto tempo o item mais antigo está em `EM_ANALISE`. Sem isso, "pico absorvido" e
  "afogando" são indistinguíveis de fora: no teste de carga não houve erro, nem latência ruim, nem
  alerta — só 69 mil avaliações paradas.
  *Pronto quando:* idade do item mais antigo exposta por faixa em `/actuator/prometheus`, com
  alerta.

- [ ] **Chaos e carga**
  Nenhum teste de banco fora, Kafka fora, provider lento ou retornando lixo.
  *Pronto quando:* SLIs/SLOs definidos (hoje **não há requisito não-funcional documentado**) e
  os cenários da auditoria §15 rodam no CI.
  *Parcial:* o arnês de carga passou a existir (k6, `POST`+`GET` de avaliação com ramp em degraus)
  e produziu os números dos três itens acima — mas roda à mão e não está no CI. O `findAll()` do
  match por nome continua sendo o teto: a medição de 12,5/s foi feita com ~67k linhas de watchlist
  carregadas por avaliação.

---

## 🔒 Bloqueado por decisão ou fornecedor

| Item | Bloqueio | Efeito hoje |
|---|---|---|
| Bureau real de CPF | Sem credenciais (BigBoost/Serpro). **Não existe API gratuita legítima** — o que se anuncia como tal é scraping com bypass de captcha ou base vazada | `CpfBureauReadinessGuard` impede prod; PF inviável. Dev usa o `FakeCpfBureauProvider` com cenários por prefixo ([bureau-simulado.md](bureau-simulado.md)); o mapeamento de `TaxIdStatus`/`HasObitIndication` já está implementado e testado contra o JSON documentado, então contratar é só ligar a flag |
| Validação do CSV de PEP | 403 do ambiente para o Portal da Transparência | Fonte escrita sem verificação |
| Provedor KYB (UBO) | Contrato | KYB só de 1º grau |
| Mídia negativa real | Contrato | `StubNegativeMediaProvider` com lista vazia |
| Volumetria/SLA alvo | Decisão de produto | "Escalável" não é afirmação verificável sem meta. A capacidade atual já está medida (ingestão 292 req/s · processamento ~12,5/s simulado, ~3/s com bureau real) — falta o alvo, não o número |
| Dimensionamento de workers e cap de bureau | Contrato BigBoost | Limite de concorrência, suporte a consulta **em lote** e preço por faixa de volume são desconhecidos. Se a API aceitar lote, a vazão muda mais que qualquer paralelismo. Até lá, default conservador e configurável ([ADR-0015](../adr/0015-ingestao-em-massa-faixa-separada.md)) |

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
