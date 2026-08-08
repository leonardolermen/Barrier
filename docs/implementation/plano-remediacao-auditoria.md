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

| | Auditoria inicial | Agora | Δ |
|---|---|---|---|
| Testes | 141 (+2 erros) | **244** (0 falhas) | +103 |
| Achados 🔴 Critical | 27 | **12** | −15 |
| Nota geral | 3,2 | **≈5,2** | +2,0 |

Ramos entregues:

| Branch | Commit | Conteúdo |
|---|---|---|
| `fix/hardening-go-live` | `bdecade` | Fail-open de decisão, gate de admin, segredos, PII em log, comparação de nome |
| `feat/pep-watchlist-cgu` | `d671e72` | Fonte de PEP da CGU, cobertura de listas verificável e fail-closed |
| `feat/tenant-api-key` | `e1792cd` | Autenticação por API key; tenant derivado da credencial |
| `fix/processing-integrity` | — | Reivindicação exclusiva, transação por avaliação, estado de falha |

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

- [ ] **`TaskScheduler` dedicado por job**
  Pool default é **1 thread** compartilhada por processador, relay e importação diária. O import
  das 03:00 congela o pipeline; Kafka lento (`.join()`) congela tudo.
  *Pronto quando:* import de watchlist não bloqueia processamento (teste ou medição).

- [ ] **Timeouts e circuit breaker em todos os clients**
  `HttpWebhookClient` usa `RestClient.create()` **sem timeout algum**, na thread do listener
  Kafka: endpoint lento do cliente para a partição.
  *Pronto quando:* todo client tem connect+read timeout; provider em falha abre o breaker.

- [ ] **Endpoint de webhook por tenant**
  `barrier.webhook.target-url` é **global**: com dois tenants, um recebe as decisões de KYC do
  outro. Vazamento cross-tenant por desenho.
  *Pronto quando:* a entrega resolve a URL pelo `tenantId` do evento.

- [ ] **Não perder evento por falha transitória**
  `AssessmentCompletedListener` engole toda `RuntimeException` e commita o offset: banco fora do
  ar = decisão perdida para sempre, sem DLQ e sem reconciliação.
  *Pronto quando:* falha transitória não commita; existe DLQ e job que reconcilia
  `assessments` concluídos contra `deliveries`.

- [ ] **Observabilidade mínima**
  MDC só existe na thread do servlet — **os logs da decisão não têm `correlationId` nem
  `assessmentId`**, porque a decisão acontece no `@Scheduled`. Investigar uma aprovação indevida
  hoje é `grep` em log texto por documento mascarado.
  *Pronto quando:* MDC no processador; log JSON; micrometer + Prometheus; e os 5 alertas:
  watchlist vencida, `EM_ANALISE` > 15min, outbox `PENDING` > 5min, delivery `DEAD`, taxa de
  aprovação fora da banda.

- [ ] **`JSONB` nas colunas de evidência**
  `hits_json`, `results_json`, `factors`, `partners_json` são `VARCHAR(4000)`/`(2000)`: estouro
  vira exceção → poison pill, e **o que não cabe é justamente a evidência de auditoria**.
  *Pronto quando:* colunas migradas; QSA grande e múltiplos matches não quebram.

- [ ] **Persistir também as regras que não dispararam**
  `RiskScoringService` filtra por `triggered()` antes de gravar: não dá para provar que a regra
  de sanção *rodou e passou* — indistinguível de "estava desligada" ou "a lista estava vazia".
  *Pronto quando:* `results_json` traz todas as regras avaliadas com flag `triggered`.

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

- [ ] **Screening dos sócios PF e do representante legal**
  Hoje o screening consulta só o CNPJ. Empresa limpa com sócio sancionado é aprovada — o sócio
  nunca é consultado.
  *Pronto quando:* teste com sócio em lista resulta em escalonamento.

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

- [ ] **Histórico versionado de configuração**
  `tenant_risk_config`, `risk_rule_registry` e `subject_profiles` são mutáveis sem histórico —
  e o snapshot da watchlist usada não é preservado. Não dá para responder "quais regras estavam
  ativas, com que parâmetros, contra qual lista" numa decisão de 8 meses atrás.
  *Pronto quando:* uma decisão antiga é reproduzível a partir do que está gravado.

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

- Mudança de regra ou peso **sobe `ENGINE_VERSION`** (atual: `barrier-risk-rules/1.2.0`).
- Bug corrigido vem com teste de regressão que **falha antes** da correção.
- Controle novo que possa faltar em produção ganha um `ReadinessGuard` no padrão dos existentes:
  falha a subida em `prod`, avisa nos demais profiles.
- Ao fechar um item, atualize a tabela **Onde estamos** e mova para ✅ com o commit.
