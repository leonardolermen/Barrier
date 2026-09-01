> **📦 ENCERRADO em 2026-08-31 — documento de arquivo, não é backlog.**
> O backlog vivo é [docs/product/backlog.md](../../product/backlog.md). **P0 inteiro fechado**;
> P1–P4 migraram. Preservado aqui: as notas por dimensão, o padrão encontrado ("o desenho está
> acima da média; a operação do que foi construído está abaixo"), as duas recusas com critério
> (regra customizável pelo parceiro; "trocar a BrasilAPI") e a lista do que **não** mudar.

# Plano de auditoria externa — 2026-08-18

Documento vivo. Origem: auditoria técnica, arquitetural e de produto de
`feat/origem-f8-f9-rekyc` (commit `e141669`), feita com leitura de código — não de README.

**Como usar:** igual ao [plano-remediacao-auditoria.md](plano-remediacao-auditoria.md) — cada
item tem critério de pronto **verificável**, não "implementar X" mas "como sabemos que X
funciona". Marque `[x]` só com teste cobrindo, e registre o commit ao lado.

**Relação com o plano de remediação:** aquele nasceu de auditoria de *segurança e integridade
da decisão* e ainda tem itens abertos válidos — este **não o substitui**. A diferença de foco:
aquele pergunta "a decisão está correta?"; este pergunta "isto vira produto em produção?".
Onde há sobreposição, o item aparece aqui com ponteiro para lá, sem duplicar o texto.

---

## O que a auditoria concluiu (para não perder o contexto)

Notas (0–10): Arquitetura 7,5 · Código 8,5 · Segurança 5,0 · Performance 3,5 ·
Escalabilidade 3,0 · Risk Engine 8,0 · KYC/KYB 5,0 · Webhooks 5,0 · Observabilidade 6,0 ·
Testes 6,5 · API 4,5 · DX 3,0 · Compliance 5,5 · Multi-tenancy 6,0 · Product readiness 3,5 ·
Production readiness 3,5 · Enterprise readiness 2,0. **Final: 6,0.**
Maturidade: **produto early-stage**, na borda inferior.

**O padrão encontrado** (equivalente ao "falha aberto onde deveria falhar fechado" da auditoria
anterior, e que deve ser usado para avaliar qualquer item novo daqui em diante):

> **O desenho e a disciplina estão muito acima da média; a operação do que foi construído está
> muito abaixo.** Não existe CI, não existe container, dois módulos de API estão inacessíveis,
> quatro integrações de produção nunca viram um byte do parceiro, e três loops de processamento
> são sequenciais. Nada disso é problema de arquitetura — é problema de *sequenciamento*.

**O corolário, que é a decisão mais importante deste plano:** o veredito da auditoria foi
*continuar, não recomeçar* — o modelo de domínio, as fronteiras e a transacionalidade estão
corretos. Em troca disso, **escopo novo fica congelado até P0 e P1 fecharem**. F5/F7/F8/F9
foram entregues enquanto não havia CI e com duas APIs mortas: é o padrão de construir pelo
problema interessante em vez do problema bloqueante, e é o principal risco do projeto hoje.

---

## Escopo corrente decidido (2026-08-19)

Pergunta que originou esta seção: *"o próximo passo para virar produto regulatório é regras
customizáveis pelos parceiros, ou trocar a BrasilAPI pelo BigBoost/Serpro?"* — as duas
recusadas **na forma como foram propostas**, pelos motivos abaixo. O que ficou no lugar:

| # | Item | Onde está detalhado |
|---|---|---|
| 1 | **P0 inteiro** — auth de `mesa`/`behavior`, actuator, CI+Docker, JSONB, handler | P0, abaixo |
| 2 | **Replay + política versionada com vigência e autoria** | P3 → promovido |
| 3 | **Identidade de operador + 4-eyes + fim da admin key global** | P2 → promovido |
| 4 | **Fonte de QSA contratada** (o que "trocar a BrasilAPI" de fato significa) | P3 → promovido |

**Adiado por decisão explícita:** criptografia em repouso + retenção. É o 5º da mesma lista e
segue sendo bloqueador de questionário de segurança — adiado, não resolvido, e volta assim que
os quatro acima fecharem.

O ordenamento é por *"uma instituição regulada assinaria isto?"*, não por esforço. Os quatro
são sequenciais no sentido fraco: o 1 bloqueia todos (sem CI, cada um dos outros é aposta sem
rede), e o 2 e o 3 se reforçam (replay sem autoria responde "o quê" mas não "quem").

### Por que **não** regras customizáveis pelo parceiro

Recusa com critério, no mesmo padrão do schema registry (F9) — para a pergunta não voltar em
três meses sem o racional junto.

O `TenantRiskConfigValidator` já permite override por tenant de `NEW_COMPANY` e
`SENSITIVE_CNAE`, com `cnae-codes` **unido** ao default e nunca substituído, e regra regulatória
sem `rule_code` conhecido pelo validador. Isso é **operação interna/admin**, e a decisão de não
ser self-service está no `CLAUDE.md`: *"deixar o próprio tenant relaxar seus controles é o risco
que a validação existe para evitar"*.

Abrir ao parceiro dá ao regulado o botão de afrouxar o próprio controle de PLD-FT — e o
compliance officer do comprador **não quer** que a área de negócio dele tenha esse botão. Não é
limitação a resolver: é a postura que torna a plataforma vendável a um regulado.

E não é o que trava a venda. O que trava é que a plataforma não responde *"qual política estava
vigente quando este cliente foi aprovado, e quem aprovou essa política"*. A `config_history`
(V033) **já é gravada** e nada a lê — só o repositório que a escreve a referencia. O dado da
resposta existe; a resposta, não. Se o que se quer atrás de "regra customizável" é flexibilidade
por parceiro, a forma regulatória disso é **política versionada com vigência, autoria e replay**
(item 2), não editor de regra. Regra-como-dado editável em runtime sacrificaria o
`ENGINE_VERSION` e a trilha reproduzível — recusa já registrada no F8.

### Por que "trocar a BrasilAPI" é, na verdade, "contratar QSA"

O instinto está certo e o código já avisa: `CnpjBureauReadinessGuard` grita na subida que a
BrasilAPI é *"API pública gratuita, sem SLA e sem contrato"* sustentando controle regulatório.

Mas `BrasilApiBureauProvider` é a **única fonte de QSA do repositório** —
`BigBoostCnpjBureauProvider.toProfile` devolve `List.of()` em sócios, porque o `basic_data` de
empresas não traz quadro societário. Trocar hoje, sem dataset substituto, faria:
`CorporateStructureRiskRule` parar de disparar (sócio estrangeiro/PJ deixa de ser detectado) e
`CorporateStructureCoverageRiskRule` — fail-closed de propósito — disparar em **100% das PJ**.
Resultado: toda pessoa jurídica em revisão manual, sempre. **KYB automatizado iria a zero.**

Logo o item não é troca de provider, é **aquisição de fonte com QSA** (dataset separado e pago
na BigDataCorp; outro endpoint no Serpro). Só depois a BrasilAPI vira fallback ou sai. Isso
funde este item com o **UBO ≥25%** do P3, que é o teto real do KYB hoje.

⚠️ `SerproBureauProvider` é **esqueleto morto**: sem `@Component`, `check()` só lança
`BureauUnavailableException`. E o plumbing Serpro que de fato existe (`SerproGatewayConfig`,
Datavalid) nunca teve egress de rede validado. Serpro não é "trocar", é integrar do zero e
verificar ao vivo — some com o item de integrações não verificadas do P1.

---

## 🔴 P0 — Bloqueadores (dias, antes de qualquer coisa)

- [x] **`/v1/mesa/**` e `/v1/behavior-events` estão fora do filtro de autenticação** 🔴 — **fechado 2026-08-19**
  Corrigido invertendo para denylist em `ApiRoutes` (fonte única consultada pelos dois filtros):
  tudo sob `/v1/` exige tenant, administração é a exceção declarada. `ApiRouteCoverageTest`
  enumera os controllers e exige classificação de todos, com guard antivácuo — endpoint novo
  nasce protegido e o esquecimento passa a falhar do lado seguro (401 em rota que deveria ser
  admin aparece na hora; rota de negócio servida sem credencial, não). 12 testes novos verdes.
  <details><summary>diagnóstico original</summary>
  `TenantAuthenticationFilter.PROTECTED_PATHS` é `^/v1/(assessments|subjects)(/.*)?$`. Mesa e
  behavior não casam e também não estão em `ADMIN_PATHS`. O `TenantArgumentResolver` lança
  `IllegalStateException` (falhou fechado por bom desenho defensivo), que o
  `ProblemExceptionHandler` mapeia para **409 com a mensagem interna**. Consequência dupla:
  **F7 e F8 são inteiramente inacessíveis** — a mesa de análise e a ingestão comportamental não
  funcionam, para ninguém — e um chamador anônimo recebe detalhe de arquitetura interna.
  A causa raiz não é o regex: é a **allowlist de protegidos**. Endpoint novo nasce aberto e
  nada no código aponta o erro, que é exatamente o problema que o `TenantArgumentResolver`
  existia para tornar impossível — ele tornou impossível *servir sem tenant*, não *esquecer o
  filtro*. Inverter: o filtro cobre `/v1/**` e as exceções (actuator, admin) são listadas.
  *Pronto quando:* teste de camada web para **cada** grupo de rota provando 401 sem credencial
  — e um teste que enumera todos os `@RequestMapping` de `/v1` e falha se algum não estiver
  coberto pelo filtro (o teste que faltava, não o caso que faltava). ✅ ambos.
  </details>

- [x] **Nenhum teste de camada web em `mesa` e `behavior`** 🔴 — **fechado 2026-08-19**
  `CaseApiIntegrationTest` (5) e `BehaviorEventApiIntegrationTest` (6) cobrem as três coisas que um
  teste de domínio não alcança: **auth** (enumerando cada grupo de rota, porque foi um grupo
  inteiro ficar fora da allowlist que criou o problema), **escopo de tenant** (caso ausente responde
  igual para qualquer tenant — senão vira oráculo de id; idempotência do behavior é por tenant) e
  **contrato de status** (202 com `duplicate`, 400 em fila inexistente, 200 com lista vazia).
  É por isso que o item acima passou. `CaseServiceTest`, `SlaClockTest` e
  `BehaviorEventServiceTest` cobrem o domínio com qualidade; a fronteira HTTP não tem nada.
  Não é lacuna de cobertura, é lacuna de **categoria** — e o custo dela foi duas entregas
  inteiras não funcionarem sem ninguém saber.
  **Parcial (2026-08-19):** a parte de *auth* está coberta (`TenantAuthenticationFilterTest` +
  `ApiRouteCoverageTest`). Faltam **escopo de tenant** e **contrato de status** por controller,
  que exigem Testcontainers — e o Docker local estava parado, o que é exatamente o argumento
  do item de CI abaixo.
  *Pronto quando:* cada controller tem teste de fronteira (auth, escopo de tenant, contrato de
  status), no padrão de `WebhookEndpointApiIntegrationTest`.

- [x] **`/actuator` sem autenticação** 🟠 — **fechado 2026-08-19**
  Porta de gestão separada (9090/9091), ausente do `Service`. Porta em vez de filtro de propósito:
  filtro dependeria de acertar o padrão de rota de novo, que é como `/v1/mesa` ficou aberto.
  `ActuatorPortIsolationTest` afirma pela **porta pública** que os endpoints dão 404, que a de
  gestão serve as probes, e que as duas são de fato distintas (senão o primeiro passaria por
  acidente).
  `management.endpoints.web.exposure.include=health,info,metrics,prometheus` e **nenhum filtro
  cobre `/actuator`** (não há Spring Security no classpath). `/actuator/prometheus` entrega
  volume por tenant, taxa de aprovação/recusa e profundidade de fila: inteligência competitiva,
  e sinal de fraude — um atacante observa em agregado se a tentativa dele passou.
  `health.show-details: when-authorized` também não tem authz configurado.
  *Pronto quando:* actuator em porta separada não exposta, ou atrás de auth; teste provando
  401/404 na porta pública.

- [x] **Não existe CI, não existe Dockerfile, não existe IaC** 🔴 — **fechado 2026-08-19** (`d5bb9cc`)
  CI em `.github/workflows/ci.yml` (verify com Testcontainers + build das imagens + scan de CVE),
  `Dockerfile` multi-stage e manifests em `deploy/k8s/`. Ver
  [plano-escala-horizontal.md](plano-escala-horizontal.md).
  `find -iname Dockerfile` retorna vazio; não há `.github/`. O `docker-compose.yml` sobe só
  Postgres e Kafka de dev. Consequências: a suíte de 684 testes é verde **por alegação** (nunca
  em pipeline); todo o desenho de lease/`SKIP LOCKED`/`@Version` existe para réplicas que
  **nunca subiram**; nenhum CVE de dependência é detectado num stack de vanguarda (Java 25 +
  Boot 4.0); e cada correção deste plano é uma aposta sem rede.
  **Este item bloqueia todos os outros** — é o primeiro a fechar. Aberto em profundidade em
  [plano-escala-horizontal.md](plano-escala-horizontal.md) (container, ciclo de vida, partições
  do Kafka, locks nos jobs singleton, autoscaler por profundidade de fila e prova em `kind`) —
  **em execução**.
  *Pronto quando:* push roda `mvnw verify` com Testcontainers e falha o build; imagem publicada
  e subindo com `docker compose up`; scan de dependência ativo; badge no README.

- [x] **`JSONB` nas colunas de evidência** 🟠 — **já estava resolvido; item da auditoria estava desatualizado**
  A **V026** já converteu `hits_json`, `results_json` e `partners_json` para JSONB (e `factors`
  para TEXT), depois de um CNPJ com 41 sócios derrubar a avaliação inteira. A auditoria listou o
  item como aberto sem conferir as migrations — vale como lembrete de que achado de auditoria
  também precisa de verificação.

  **Mas a varredura encontrou o que a V026 não pegou:** `outbox.payload` e `deliveries.payload`
  continuavam `VARCHAR(4000)`. Corrigido em V047/V007 — preventivo, não corretivo (os três
  eventos de hoje têm forma fixa e campos curtos), porque o modo de falha ali é pior: a gravação
  da outbox roda na **mesma transação** que conclui a avaliação, então payload grande não perde o
  evento — **reverte a conclusão**, e a avaliação refaz as consultas pagas até
  `FALHA_PROCESSAMENTO`. Basta alguém acrescentar os fatores explicáveis ao payload para o teto
  voltar a morder.

  ⚠️ **TEXT, não JSONB**, ao contrário da V026: `WebhookDeliveryService` calcula o HMAC sobre o
  payload **lido do banco** e envia essa mesma string. JSONB normaliza (reordena chaves, remove
  espaços), então o parceiro receberia bytes diferentes dos serializados pelo produtor. Fidelidade
  de bytes é requisito nessa coluna; a consultabilidade que justificou JSONB na evidência não vale
  o risco.

- [x] **`IllegalStateException → 409` genérico vaza mensagem interna** 🟢 — **fechado 2026-08-19**
  `AssessmentStateException` e `AssuranceDisabledException` no padrão do
  `DocumentGateNotSatisfiedException`; o handler genérico saiu e esses casos viram 500 sem detalhe.
  **O tipo da exceção passou a ser a decisão do que é público.** `ProblemExceptionHandlerTest` é
  reflexivo e falha se qualquer handler voltar a tratar exceção genérica da plataforma — com
  `IllegalArgumentException → 400` registrada como exceção consciente à regra, para não ser
  "corrigida" depois por simetria.
  O handler devolve `e.getMessage()` ao chamador. Foi assim que a mensagem
  "Rota declara AuthenticatedTenant mas não está coberta pelo filtro" ficou pública. Mapear as
  causas legítimas (kill switch desligado etc.) em exceções próprias — o projeto já faz isso
  certo em `DocumentGateNotSatisfiedException`, com o racional escrito — e deixar o genérico
  virar 500 sem detalhe.
  *Pronto quando:* nenhum `@ExceptionHandler` devolve `getMessage()` de exceção não-modelada.

---

## 🟠 P1 — MVP (1–3 meses)

- [x] **`findNameEntries()` é `jpa.findAll()`: a watchlist inteira em heap, por avaliação** 🔴 — **fechado 2026-08-19** (branch `perf/screening-indexado`)
  Blocking por trigrama (`pg_trgm`, operador `<%`, V048). **31× mais rápido** com 100 mil entradas:
  360ms → 13ms, medido, não estimado. Pior caso (`JOSE SILVA`, os tokens mais comuns da base)
  traz 11,6% da base em vez de 100%.

  **O benchmark reprovou duas implementações minhas antes de aprovar a terceira**, e esse é o
  registro que importa:

  | Versão | Tempo | Plano do Postgres |
  |---|---|---|
  | `findAll()` original | 360ms | 100.000 linhas materializadas |
  | `EXISTS (unnest(...))` | 939ms | seq scan + subconsulta por linha |
  | predicados `OR` | 946ms | **seq scan** — o planner recusou o índice |
  | `UNION` | **13ms** | bitmap index scan por ramo |

  Com **um** token o `OR` virava `BitmapOr` sobre o GIN em 2,5ms; com **três**, o planner estimou
  que três bitmap scans custam mais que varrer e escolheu `Seq Scan`, avaliando `<%` nas 100 mil
  linhas três vezes (`Rows Removed by Filter: 100002`). **"Usa índice" não é propriedade do SQL, é
  decisão do planner** — e muda com o número de predicados e o tamanho da tabela. `UNION` torna o
  plano determinístico.

  Também vale registrar uma hipótese **errada** que custou uma rodada: atribuí o custo a
  dirty-checking de 100 mil entidades JPA deixadas pelo aquecimento; reordenei as medições e o
  número não mudou. Só o `EXPLAIN ANALYZE` da consulta *exata* (com o mesmo número de tokens e o
  mesmo limiar) deu a resposta — o EXPLAIN que eu tinha rodado antes usava 1 token e o limiar
  default, e por isso mostrava um plano que a aplicação nunca executava.

  Duas decisões de segurança no desenho: **normalização em Java, nunca em SQL** (duas
  implementações divergem, e a divergência vira candidato não encontrado — falso negativo
  silencioso); e **fail-open** — `name_normalized` nasce NULL e essas linhas entram sempre como
  candidatas, então enquanto a coluna não estiver preenchida o comportamento é o antigo: mais
  lento, nunca menos abrangente.
  `WatchlistEntryRepositoryImpl:119`. O `FuzzyNameWatchlistProvider` materializa **a tabela
  toda** e roda Jaro-Winkler token a token sobre ela, uma vez por avaliação (o `searchAll`
  batendo todas as partes de uma vez já economiza o que dava — o problema é o `findAll`). Em
  produção com OFAC (SDN+ALT, cada alias uma linha) + CSNU + CEIS/CNEP + PEP da CGU, isso é da
  ordem de **10⁵–10⁶ linhas materializadas por avaliação**; a 12/s são milhões de objetos por
  segundo. GC thrash e p99 explodindo.
  **O que torna isto o item mais grave do repositório:** o custo cresce com a **cobertura de
  listas**, ou seja, *melhorar o compliance degrada a plataforma*. É um incentivo invertido
  dentro do sistema — a mesma classe de problema que o SLA pausável da mesa foi desenhado para
  evitar do lado da operação.
  *Pronto quando:* candidatos vêm do banco por índice (`pg_trgm` + GIN, ou blocking por chave
  fonética) e o fuzzy roda só sobre eles; benchmark com **500k entradas reais** mostrando p99 e
  alocação estáveis; e o golden dataset abaixo provando que **o recall não caiu**.

- [x] **Golden dataset rotulado de screening + calibragem por recall** 🔴 — **fechado 2026-08-19**
  `golden-dataset.csv` (48 pares rotulados) + `ScreeningRecallTest` (curva) +
  `BlockingRecallIntegrationTest` (o blocking não descarta nenhum par que o algoritmo casaria).
  A curva mostra 0.90 numa região **plana** — recall 1.00 de 0.80 a 0.94 —, o que transforma
  "0.90 porque pareceu razoável" em "0.90 porque a região é estável".

  **O conjunto criticou a si mesmo:** a primeira versão dava precisão 1.00 até com limiar 0.80,
  sinal de que os negativos eram fáceis demais e o eixo de falso positivo não era exercitado por
  nada. Com negativos difíceis o teste quebrou e revelou o trade-off real — a 0.90 casam
  `SILVA`×`SILVEIRA`, `PINTO`×`PINHO`, `CLAUDIA`×`CLAUDIO`, `ANDRADE`×`ANDRADA`. É a decisão
  **certa**: 0.96 levaria a precisão a 1.00 e o recall a 0.92 — trocaria quatro revisões manuais
  por um sancionado não encontrado.

  ⚠️ **Limitação declarada no próprio CSV:** o conjunto é sintético e estrutural. Trava o
  comportamento do algoritmo nos padrões conhecidos; **não** estima recall de produção. Casos reais
  rotulados por analista, com a distribuição de verdade, seguem sendo trabalho próprio — e é isso
  que fecharia a calibragem para um regulador.
  Item já aberto no plano de remediação, promovido aqui a pré-requisito do anterior. O limiar
  0.90 por token foi escolhido por raciocínio — bom raciocínio, registrado — e não por curva de
  recall/precisão. Sem conjunto rotulado, não há como provar que o índice do item acima não
  perdeu ninguém, e é isso que separa controle de teatro.
  *Pronto quando:* conjunto rotulado versionado no repo; teste que falha se o recall cair abaixo
  do piso; a escolha do limiar documentada pela curva, não pelo argumento.

- [ ] **Sem cota nem rate limit por tenant** 🔴 — *já aberto, ver ADR-0015*
  Fecha três coisas de uma vez: DoS trivial (290 req/s medidos de ingestão), noisy neighbor
  (um tenant em bulk **para o onboarding de todos**) e sangramento de fatura de bureau
  ilimitado. O re-KYC periódico já documenta a mesma limitação com a mesma solução pendente
  ("o teto é global e a ordem por antiguidade não isola tenants") — é uma solução, dois
  consumidores.
  *Pronto quando:* cota por tenant no intake **e** no lote de processamento; teste provando que
  tenant em bulk não atrasa o p99 do tenant vizinho.

- [ ] **Ninguém consegue integrar sem falar com o time** 🟠 — *parcial: OpenAPI fechado em
  2026-08-31 (`77b3707`); faltam guia publico, sandbox exposto, SDK e catalogo de reason codes*
  Sem OpenAPI (só um comentário no pom dizendo "Fase 5"), sem SDK, sem sandbox, sem changelog
  de API, sem catálogo público de reason codes, sem doc de como verificar o HMAC nem o que fazer
  com `X-Barrier-Signature-Previous`. O que existe é uma collection do Postman e docs internas
  em português para quem já conhece o domínio. O que está bom e deve ser preservado: RFC 7807
  consistente, semântica de `Idempotency-Key`, `/v1/` desde o início, `event-catalog.md`.
  *Pronto quando:* um dev externo integra intake + webhook lendo só a doc pública, sem contato.

- [ ] **Paginação inexistente** 🟡
  A fila da mesa usa `limit` cru sem cursor; `GET /v1/risk-rules` devolve tudo; não há listagem
  de avaliações, então o parceiro não tem como reconciliar sem o webhook.
  *Pronto quando:* cursor estável em toda coleção; teste de página com inserção concorrente.

- [ ] **Quatro integrações de produção nunca viram um byte do parceiro** 🔴
  BigBoost CNPJ ("o schema ainda não foi verificado contra a API real"), Datavalid/Serpro ("sem
  egress de rede neste ambiente"), CSV da CGU ("403 do ambiente de dev, inclusive para `ceis`")
  e PagerDuty ("nunca exercitado"). Todas mapeadas verbatim da documentação — e documentação
  mente. É o **risco de cronograma mais subestimado do projeto** e o único que não se resolve
  com mais código.
  *Pronto quando:* uma chamada real, com credencial de teste, gravada como fixture de contract
  test para cada uma das quatro.

- [ ] **Documentoscopia e biometria devolvem `UNAVAILABLE` em produção** 🟠
  `UnavailableDocumentVerificationProvider`/`UnavailableBiometricVerificationProvider` são os
  providers de `prod`. O pipeline está pronto e correto (consentimento na assinatura, gate
  documento→biometria, divergência vs campo faltando, reavaliação automática); **a capacidade
  não existe**. Um comprador de KYC PF quer selfie + documento, e hoje a resposta é "temos a
  arquitetura". Com o gate de documentoscopia obrigatório, a ausência de provedor **trava a
  frente inteira**, não metade.
  *Pronto quando:* um provedor contratado, exercitado ao vivo, e o `AssuranceProviderReadinessGuard`
  deixando de avisar em `prod`.

---

## 🟡 P2 — Primeiros clientes (3–6 meses)

- [ ] **Paralelizar os três loops sequenciais** 🔴
  `AssessmentProcessor.process()` reivindica 50 e processa **um por um** com HTTP de bureau no
  meio; `OutboxRelay` faz 100 com `.join()` serial; `WebhookDeliveryService.retryDue()` posta
  100 em sequência. Três gargalos, **uma causa raiz: nada é paralelo.** Números medidos pelo
  próprio autor: ingestão 292 req/s vs processamento 2,7–12,5/s com bureau **simulado** — a
  assimetria de ~100x é o que produziu 69.809 avaliações presas em `EM_ANALISE`. Enfileirar
  100x mais rápido do que se processa não é fila, é vazamento.
  **Ordem importa:** depois da cota (P1) e do índice de screening (P1) — paralelizar antes só
  multiplica o consumo de bureau e o `findAll` da watchlist.
  *Pronto quando:* concorrência configurável com isolamento por tenant; teste de carga de 1h a
  100 req/s com bureau real mostrando profundidade de fila **estável**, não crescente.

- [ ] **Webhook-api: a corretude é alta, a máquina de entrega é protótipo** 🟠 — nota **5/10**
  O que está certo e deve ser preservado: HMAC por tenant, rotação com overlap e header duplo,
  idempotência por `eventId`, lease no `claimDue`, backoff com teto 64x, DLT, reconciliação pelo
  tópico, TLS obrigatório, destino pelo tenant **do evento**.
  O que reprova para "milhares de aplicações clientes": entrega **serial** (~10–20/s por
  instância; 100 destinos lentos a 10s de timeout = **1000 segundos** de lote), **zero
  isolamento entre tenants** (a primeira tentativa roda na thread do listener Kafka — um destino
  que aceita conexão e demora **para o consumo da partição**, e o timeout mitiga sem resolver),
  sem rate limit por destino (o Barrier pode DDoSar o cliente), sem circuit breaker (a decisão
  de omitir está registrada e está **errada**: sem breaker, cada retry de um tenant morto paga o
  timeout inteiro e ocupa um slot do lote global), sem API de histórico nem re-envio (quando um
  cliente disser "não recebi", a resposta é SQL — suporte nível 3 virando produto), sem métricas
  de entrega (as regras do `alerts.yml` estão escritas e **inertes**, o que é pior que ausência:
  dá cobertura falsa).
  **Não é ajuste, é componente novo:** worker pool com fila por destino, breaker por endpoint,
  concorrência configurável, e o contrato de ordem documentado ao cliente antes de paralelizar
  (hoje a chave `assessmentId` garante ordem por agregado; a paralelização quebra isso em
  silêncio).
  *Pronto quando:* 1.000 endpoints com 10% lentos e o tenant saudável **sem atraso mensurável**;
  API de histórico e re-envio; breaker com teste; métricas ligadas nos alertas que já existem.

- [ ] **Criptografia em repouso + retenção** 🟠 — *já aberto na Fase 6*
  Grep por `encrypt|Cipher|retention` no projeto: **zero**. Em claro hoje: CPF/CNPJ, nome,
  nascimento, endereço, `raw_response` do bureau (dado pessoal, indefinidamente) e **o segredo
  HMAC na coluna**. Um dump de backup entrega a base de todos os tenants. Sem política, sem job
  de expiração, sem coluna de prazo. Bloqueia o questionário de segurança de qualquer comprador
  sério — antes de bloquear qualquer norma.

- [ ] **UI mínima da mesa de análise** 🟠
  O módulo `mesa` está com o domínio certo (SLA derivado da timeline, pausa exigindo o par
  pedido→recebimento, ações append-only) e **um analista não usa `curl`**. Sem UI, F7 é
  infraestrutura para um produto que ninguém opera.

- [ ] **Scheduler dedicado por job** 🟡
  8+ jobs (`AssessmentProcessor`, `OutboxRelay`, `WatchlistImporter`, `AlertEvaluator`,
  `AssuranceResultPoller`, `PeriodicReassessmentJob`, purga de idempotência,
  `DeliveryReconciliationJob`) em **4 threads**. A importação das 03:00 e o re-KYC das 03:30
  competem com o relay. O plano de remediação já registra que 4 threads era "suficiente para os
  três jobs atuais" — não são mais três.

- [ ] **Métricas de outbox, cobertura de watchlist e entrega de webhook** 🟡 — *já aberto*
  As três regras correspondentes no `alerts.yml` estão escritas e não podem disparar.

- [ ] **SSO/OIDC + RBAC + identidade de operador + 4-eyes** 🟠 — *já aberto na Onda 1*
  `reviewed_by` é texto livre; `reviewed_by_key` identifica o **sistema**, não a pessoa. E a
  chave de admin é **estática, única e global**: ela liga/desliga regra regulatória e emite
  credencial de qualquer tenant, sem rotação e sem autoria. Comprometimento = controle total da
  plataforma sem trilha.

- [ ] **Redesenhar `arquitetura-atual.svg`** 🟢
  Desenha 4 módulos de 15 e cita no rodapé módulos que nunca existiram. Ver
  [docs/diagrams/README.md](../../diagrams/README.md), já marcado como desatualizado.

---

## 🔵 P3 — Escala (6–12 meses)

- [ ] **Replay de decisão com a configuração da época** 🟠
  Este é o item de maior retorno por esforço do plano inteiro, e quase ninguém percebe que está
  quase pronto: `evaluated_json` (com regras suprimidas e parâmetro efetivo), `config_history`
  (V033), snapshot de versão de lista, `identity_check_id`/`screening_result_id` exatos e
  `ENGINE_VERSION` **já existem**. Os dados estão lá; **a capacidade não**. Não há endpoint nem
  serviço que reexecute uma decisão histórica. Auditoria reproduzível está *habilitada pelos
  dados* e *ausente como produto* — e é o primeiro pedido de um fiscal.
  *Pronto quando:* `POST /v1/assessments/{id}/replay` reproduz o desfecho histórico bit a bit e
  aponta a diferença quando o motor atual decide outra coisa.

- [ ] **Simulação / shadow mode de regra contra histórico** 🟠
  Hoje **toda mudança de regra é aposta**: não há como rodar uma regra nova sobre o histórico e
  ver o impacto antes de ligar. Com replay pronto (acima), isto é o passo seguinte natural —
  e é o que a Alloy vende como diferencial. Ver P4: pode ser produto, não só ferramenta interna.

- [ ] **Caminho síncrono de decisão** 🟠
  O pipeline é **100% assíncrono via poller**; não existe API que devolva score em <300ms. Para
  antifraude transacional — que o nome do produto promete — isso é bloqueador **arquitetural**,
  não detalhe de latência.

- [ ] **Chaos e carga em CI; multi-réplica provada** 🟠 — *já aberto*
  `ConcurrentClaimIntegrationTest` é bom e roda com **uma** instância. Duas réplicas reais
  nunca subiram. Somar: Postgres failover, Kafka fora por 5 min, bureau degradado.

- [ ] **Tracing distribuído por etapa** 🟡
  `correlationId` persistido e restaurado através de thread, scheduler e broker resolve o
  problema difícil e é excelente. Falta o fácil: **sem OpenTelemetry**, "quanto tempo cada etapa
  demorou" não tem resposta — só há timer agregado do processamento. É a única das sete perguntas
  de investigação da auditoria que o sistema **não** responde.

- [ ] **UBO ≥25% com provedor KYB** 🟠 — *já aberto, ver ADR-0018*
  **KYB sem UBO não é KYB.** O `CorporateStructureCoverageRiskRule` é honesto sobre a ausência
  — e a consequência é que **toda PJ atendida pelo bureau real vai para revisão manual**, porque
  `basic_data` da BigBoost não traz QSA. Hoje o KYB do Barrier não automatiza PJ: vender assim
  é vender fila.

- [ ] **Cache de registry e config por tenant** 🟡 — *já aberto*
  ~10 queries extras por avaliação. Não é o gargalo; é gordura no caminho quente.

---

## 🟣 P4 — Diferenciação (12+ meses)

- [ ] **Shadow mode como produto**, não ferramenta interna — o compliance officer calibra e vê
  o impacto antes de ligar. É a resposta ao no-code rule builder da Alloy, sem sacrificar o
  `ENGINE_VERSION` (a recusa de regra-como-dado está certa e deve ser preservada).
- [ ] **Antifraude, nesta ordem de custo/benefício:** device fingerprint → velocity → grafo de
  entidades (pessoa × empresa × endereço × device × conta). Hoje `behavior_events` é **tabela,
  não antifraude** — zero regras leem o acervo. O próprio plano de remediação dá 1,0/10 a
  antifraude e está correto.
- [ ] **Monitoramento transacional** (Circular 3.978) consumindo `behavior_events` — o
  consumidor que justifica o F8 retroativamente.
- [ ] **COAF/SISCOAF** e workflow de atividade suspeita.
- [ ] **Biblioteca de conectores BR** — o trabalho chato que ninguém quer fazer e que é o moat
  real da Alloy. Hoje: BrasilAPI (grátis, sem SLA), BigDataCorp e Serpro, dois deles não
  verificados.

---

## Achados de segurança não cobertos acima

Classificação e correção; os 🔴/🟠 estruturais já estão em P0/P1.

| | Achado | Correção |
|---|---|---|
| 🟡 | **Enumeration/IDOR parcial** em `/v1/subjects/{document}`: o escopo por vínculo está certo (404 sem vínculo), mas **o vínculo nasce de um `POST`** — um tenant que suspeite de um CPF cria avaliação para obter vínculo. Mitigado no cadastro (V024), não no fluxo. Sem rate limit, sondar é questão de custo. | Cota (P1) + revisar se `POST` deve criar vínculo sem intenção declarada |
| 🟡 | **SSRF residual no webhook**: URL validada só por esquema e host local. `https://169.254.169.254/…` e `https://10.0.0.5/` passam — POST autenticado para metadata do cloud ou rede interna. Gated por admin (reduz para MEDIUM, não elimina). | Negar faixas privadas/link-local **após resolução DNS**, e re-resolver no envio (rebinding) |
| ✅ | ~~**HMAC sem timestamp**~~ — **fechado 2026-08-31**: `t=<epoch>,v1=<hex>` sobre `<t>.<corpo>`, receptor de referência recusando fora da tolerância de 5 min. Feito agora porque depois do primeiro parceiro integrado seria quebra de contrato. |
| 🟡 | **Sem scan de dependência** num stack de vanguarda (Java 25 + Boot 4.0). | Entra com o CI (P0) |

**Preservar deliberadamente** (a auditoria destacou como bem feito, para não se perder numa
refatoração): comparação em tempo constante no `AdminApiKeyFilter` e no `ApiKeyMaterial`; ausência
de oráculo de erro na autenticação (chave malformada, revogada e tenant inativo respondem igual);
PII fora de log **e fora de tag de métrica, com teste travando**; `document_partial` para PEP;
redação de `MotherName` no `BureauTrace`; `subjectId` (não documento) como chave de partição.

---

## O que a auditoria disse para **não** mudar

Registrado aqui porque metade do valor de um plano é o que ele protege:

- **Monolith modular com ArchUnit.** A regra `sem_ciclos_entre_modulos` rejeitou duas tentativas
  de desenho e forçou o módulo `riskstate` e a inversão por listener. Arquitetura que empurra de
  volta é arquitetura viva — não relaxar a regra para "resolver" acoplamento.
- **Outbox + lease + claim com `SKIP LOCKED`**, e I/O externo fora de transação, nos três
  caminhos. Está certo. A paralelização (P2) constrói **sobre** isso.
- **Strategy de regras + registry + `config_history` + `evaluated_json`.** A trilha é o ativo
  mais forte do projeto e é melhor que a de fornecedores estabelecidos.
- **`bandRecommendation` limitada a REVIEW** — não somar incertezas até virar certeza.
- **Inversão por listener** (`AssessmentCompletedListener`, `AssuranceRecordedListener`,
  `SubjectProfileUpdatedListener`).
- **Separação no tipo**, não em booleano: `update` vs `enrichFromBureau`,
  `SubjectProfileService` sem assinatura que aceite só `subjectId`.
- **SLA derivado da timeline**, nunca coluna, com pausa exigindo o par.
- **`MatchBasis`**: sanção por documento reprova, por nome revisa.
- **ADR-0017** (quem não recupera o quê) e o **critério escrito** para quando o schema registry
  entra. Recusa com critério é decisão; recusa sem critério é omissão.

---

## Os quatro níveis, e a distância até cada um

| Nível | O que falta | Estimativa |
|---|---|---|
| **MVP técnico** | P0 inteiro | 2–4 semanas |
| **MVP comercial** | + OpenAPI/doc/sandbox, provedor de biometria, integrações verificadas, UI da mesa, cota | 3–4 meses |
| **Production-ready** | + paralelismo, screening indexado, webhook isolado, cripto em repouso, alertas ligados, carga e chaos aprovados, 2 réplicas provadas, runbook | 6–9 meses, 3–4 engenheiros |
| **Enterprise-ready** | + SSO/RBAC, 4-eyes, replay, retenção com legal hold, COAF, UBO, monitoramento transacional, certificação, pentest, DR com RPO/RTO | 12–24 meses |

> A distância entre production-ready e enterprise-ready **não é técnica, é organizacional** — e é
> onde o projeto de uma pessoa encontra o limite real.

---

## Próximos 6 meses (sequência, não lista de desejos)

1. **Mês 1** — P0 inteiro (auth, actuator, CI+Docker, JSONB, handler) + spike de rede nas quatro
   integrações. **Nada novo.** Só desbloquear.
2. **Mês 2** — golden dataset → screening indexado → cota/rate limit.
3. **Mês 3** — OpenAPI + doc + sandbox; provedor de biometria contratado e exercitado.
4. **Mês 4** — paralelizar os três loops; métricas e alertas ligados; carga com bureau real.
5. **Mês 5** — cripto em repouso + retenção; webhook worker pool + API de histórico.
6. **Mês 6** — UI mínima da mesa; primeiro piloto pagante em produção supervisionada.

**Sem F10. Sem módulo novo.** Seis meses tornando real o que já existe.
