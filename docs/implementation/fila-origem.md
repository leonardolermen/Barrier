# Fila de implementação — lições do BMP Origem

> **Para quem executa:** esta é a **fila**, não o plano de cada item. Cada entrada define
> escopo, arquivos, dependências e critério de pronto — o suficiente para decidir *o que vem
> agora* e para abrir a branch. Itens de código (F3 em diante) ganham plano bite-sized próprio
> em `docs/implementation/` quando entrarem em execução, no modelo do
> [reuso-de-verificacao-de-identidade.md](reuso-de-verificacao-de-identidade.md) — que é o
> plano do único item já entregue.

**Spec:** [licoes-do-origem.md](licoes-do-origem.md) (P1–P9 e "o que **não** copiar")
**Cruzamentos:** [plano-remediacao-auditoria.md](plano-remediacao-auditoria.md) ·
[risk-engine-plan.md](risk-engine-plan.md)
**Status na abertura da fila (2026-08-14):** P1 entregue (PR #19, V040, `feat/identity-reuse`).
P2–P9 intactos.

## Restrições globais

Valem para **toda** entrada desta fila; não são repetidas em cada uma.

- Camadas `controller → service → repository`; integração externa só por interface (`client`).
  Validado por ArchUnit.
- Evento só por **transactional outbox**; consumidor idempotente (Kafka at-least-once).
- Regra de risco é `RiskRule` (Strategy) com fator explicável; `ENGINE_VERSION` sobe a cada
  mudança de regra/peso. Config por tenant **não** sobe `ENGINE_VERSION`.
- Migration Flyway imutável, numeração corrente na risk-engine: **próxima livre é V043**
  (V041 = `subject_risk_state`/F3, V042 = `reassessment_decisions`/F6).
- Nunca logar CPF/CNPJ sem máscara; segredo por env.
- Toda entrada de código: teste de unidade + integração (Testcontainers) + ArchUnit quando
  cruzar módulo. Bug corrigido vem com teste.
- Frente nova nasce **desligada por flag** (`barrier.<frente>.enabled`), padrão do
  `rescreening`/`assurance`/`identity.reuse`.
- Build de aceite: `./mvnw test` verde (`JAVA_HOME=C:\Users\leona\.jdks\corretto-25.0.3`).
  `spotless:apply` não roda no JDK 25 — formatar à mão.
- Antes de escrever código, rodar a skill `barrier-implementation`.

---

## Ordem

A ordem é por (risco que remove) ÷ (custo), herdada da sequência recomendada da spec. As duas
mudanças em relação a ela: **F1 e F2 são documento e podem sair na mesma semana** (não
bloqueiam nem são bloqueados por nada), e **F6 sobe para logo depois de F5** porque só custa
uma página e precisa existir *antes* de qualquer linha de UBO.

| # | Item | Spec | Tipo | Esforço | Depende de |
|---|---|---|---|---|---|
| F1 | ✅ ADR de ownership de recovery ([0017](../adr/0017-ownership-de-recovery.md)) | P9 | ADR | ~1 página | — |
| F2 | ✅ Restrições de custo no ADR de UBO ([0018](../adr/0018-custo-de-navegacao-ubo.md)) | P6 | ADR | ~1 página | — |
| F3 | ✅ Projeção `subject_risk_state` (módulo `riskstate`) | P2 | código | médio | — |
| F4 | ✅ Webhook de mudança de nível de risco | P2 | código | médio | F3 |
| F5 | ✅ Alertas com baseline móvel (módulo `monitoring`) | P3 | código | médio | F3 (parcial) |
| F6 | ✅ Política de reavaliação + trilha ([ADR-0019](../adr/0019-politica-de-reavaliacao.md)) | P4 | código | médio | F3 |
| F7 | Mesa: fila nomeada, ações manuais e SLA pausável | P5 | código | alto | F6 |
| F8 | Ingestão de evento comportamental | P7 | código | alto | F4 |
| F9 | Catálogo de eventos + schema registry | P8 | doc + infra | — | **gatilho**, não data |

**F9 não entra na fila por ordem — entra por gatilho.** A spec é explícita: com dois
deployables e um tópico, registry é cerimônia. Ele acorda no **terceiro consumidor** de
`barrier.assessment.completed` ou no **primeiro payload que muda de forma**. Se F8 for
executado, F8 é o terceiro consumidor — então F8 dispara F9, e o catálogo entra junto.

---

## F1 — ADR de ownership de recovery (P9)

**Por quê.** Barrier tem `DeliveryReconciliationJob`, recovery do processor por lease e retry
do relay de outbox. Cada um está certo isolado; ninguém escreveu quem é dono de qual estado. É
assim que dois mecanismos recuperam a mesma coisa — no Origem isso custou solicitação
duplicada ao bureau (dinheiro).

**Entrega.** `docs/adr/0017-ownership-de-recovery.md`, no `docs/adr/template.md`.

**Conteúdo obrigatório** — uma tabela estado → dono → mecanismo → o que ele **não** faz,
cobrindo no mínimo:

| Estado | Onde vive |
|---|---|
| `assessment` em `EM_ANALISE` com lease vencido | `AssessmentProcessor` (V023 `assessment_processing_control`) |
| `outbox` não publicado | relay do outbox (V025 `outbox_claim`) |
| decisão sem `delivery` correspondente | `DeliveryReconciliationJob` (janela `PT6H`) |
| `delivery` falhada com retry pendente | `DeliveryRetryScheduler` |
| evento na `.DLT` | `DeliveryReconciliationJob` (relê o tópico), **não** o listener |
| bureau indisponível (`IdentityStatus.UNAVAILABLE`) | ninguém re-tenta hoje — decidir e registrar |

Terminar com proibições explícitas, no tom do ADR do Origem: *"O `DeliveryReconciliationJob`
**não** reprocessa avaliação; ele só cria entrega faltante."* A última linha (bureau
indisponível) é a única que exige decisão nova — as outras são documentação do que já existe.

**Pronto quando:** ADR aceito, linkado no `docs/adr/README.md` e citado no `CLAUDE.md` junto do
bloco de falha no consumo.

---

## F2 — Restrições de custo no ADR de UBO (P6)

**Por quê.** ADR-0016 etapa 4 promete "UBO até 3º grau com provider atrás de interface" e não
diz uma palavra sobre custo. Navegação societária é o caso mais explosivo que existe: cada nó
é consulta paga e a árvore não tem tamanho conhecido. Escrever isso **depois** do código é
escrever depois da fatura.

**Entrega.** `docs/adr/0018-custo-de-navegacao-ubo.md`.

**As três restrições, transcritas do `adr-derivacao-quadro-custos.md` do Origem:**

1. **Ordem por custo/benefício (D1)** — dentro de cada PJ, CPFs de beneficiários **antes** dos
   sócios PJ; depois, profundidade primeiro (esgota a subárvore de um sócio antes do irmão).
2. **Short-circuit com propagação ascendente (D2)** — sócio reprovado marca todos os PJs da
   pilha como recusado com motivo `socio_reprovado:<documento>`; sócios não analisados ficam
   `analise_suspensa`, **sem reconsulta de bureau**.
3. **Sem teto de profundidade** — parada permitida só por reprovação, nunca por largura,
   profundidade ou participação. Cortar por profundidade é mais barato e **erra**: o
   beneficiário final costuma estar no fundo.

**Amarrar ao que já existe no Barrier:** o reuso de identidade (V040) é o que torna a
navegação viável — sócio que aparece em duas árvores no mesmo TTL não paga duas vezes; mas o
reuso hoje é **só CPF**, e navegação de UBO consulta PJ. Registrar como consequência negativa
explícita: *reidratar `CompanyProfile` do `raw_response` deixa de ser opcional quando o UBO
entrar*. Registrar também que `CorporateStructureCoverageRiskRule` (V039) é o guard que impede
a árvore vazia de virar aprovação silenciosa.

**Pronto quando:** ADR aceito, ADR-0016 etapa 4 referenciando-o, `risk-engine-plan.md` Fase 6
(UBO além do 1º grau) apontando para ele.

---

## F3 — Projeção `subject_risk_state` (P2)

**Por quê.** `risk_scores` é uma linha por avaliação. Não existe "risco corrente do cliente":
não dá para responder "meus clientes em CRITICAL", não dá para avisar o parceiro quando o
risco muda, e o rescreening não sabe o que mudou em relação a antes. É pré-requisito
estrutural de F4, F5 e F6.

**Modelo.** Origem separa em três: `kyc` (operacional), `decisoes` (snapshot imutável),
`score_corrente` (vivo). O Barrier já tem os dois primeiros — `assessments` e `risk_scores`,
que nunca é sobrescrito. **Falta só a projeção viva.**

**Arquivos.**
- Criar: `services/risk-engine/src/main/resources/db/migration/V041__subject_risk_state.sql`
  — `subject_risk_state`, PK composta `(subject_id, tenant_id)` (o nível é **por tenant**:
  decisão de aceitar/recusar é por tenant no assessment, ADR-0011/0012 — projeção global
  vazaria risco entre parceiros). Colunas: `risk_level`, `risk_score`, `decision`,
  `assessment_id`, `engine_version`, `evaluated_at`, `updated_at`.
- Criar: pacote `com.barrier.riskengine.subject.state` — `SubjectRiskState` (domain),
  `SubjectRiskStateRepository` (+ interface), `SubjectRiskStateService` com
  `upsert(Assessment, RiskScore)` e `find(subjectId, tenantId)`.
- Modificar: `AssessmentProcessor` — grava a projeção **na mesma transação** da conclusão da
  avaliação (é projeção, não evento: se a avaliação commitou, o estado corrente commitou).
- Modificar: `Assessment.decide` — decisão manual (APPROVE/REJECT a partir de `EM_REVISAO`)
  também atualiza a projeção; senão o corrente fica preso no que o motor decidiu antes do
  analista.

**Regra de escrita — a que mais fácil se erra:** a projeção é **monotônica no tempo da
avaliação**, não no tempo do commit. Rescreening e reavaliação concorrentes podem concluir
fora de ordem; o `upsert` só sobrescreve se `evaluated_at` for maior que o gravado. Teste
dedicado para isso.

**Endpoint.** `GET /v1/subjects/{document}/risk-state`, escopado por tenant, 404 sem vínculo
(mesmo contrato do `GET /v1/subjects/{document}`). Fallback ao último `risk_scores` concluído
quando não houver projeção (subjects avaliados antes desta migration) — igual ao
`GET /risk/v1/clientes/{documento}/score` do Mishmar.

**Pronto quando:** avaliação concluída, decisão manual e rescreening atualizam a projeção;
teste de conclusão fora de ordem passa; `GET` responde com fallback; backfill dos subjects
existentes rodado (script de migration ou job idempotente — decidir no plano detalhado).

---

## F4 — Webhook de mudança de nível de risco (P2)

**Por quê.** É o que transforma o produto de *consulta* em *assinatura*: o parceiro passa a
saber que o cliente dele piorou sem perguntar. Só existe em cima de F3 — sem estado anterior
não há "mudou".

**Arquivos.**
- Modificar: `SubjectRiskStateService.upsert` — devolve a transição (`from`, `to`) quando o
  `risk_level` muda; nível igual não emite nada.
- Criar: evento `barrier.subject.risk_level_changed` pelo **outbox** (nunca direto no Kafka),
  payload com `tenantId`, documento mascarado conforme a convenção do
  `assessment.completed`, nível anterior, nível novo, `assessmentId` que causou,
  `engineVersion`.
- Modificar: `services/webhook-api` — consumir o tópico novo. O endpoint por tenant
  (`webhook_endpoints`) e o HMAC já resolvem a entrega; o listener novo segue o mesmo padrão
  de idempotência por `eventId` e o mesmo `DefaultErrorHandler`/DLT.

**Cuidado.** Mudança de nível causada por **reavaliação sem fato novo** não deveria acordar o
parceiro no meio da noite; a distinção vem de `Assessment.origin` (`RESCREENING`/`ASSURANCE`/
manual). Levar o `origin` no payload e deixar a política de notificação do lado do parceiro —
filtrar no Barrier seria decidir por ele.

**Pronto quando:** transição emite exatamente um evento por mudança; nível repetido não emite;
teste de integração com Testcontainers cobre o caminho outbox → tópico → entrega.

---

## F5 — Alertas com baseline móvel (P3)

**Por quê.** O teste de carga do Barrier registrou o pior modo de falha possível: 70.558
avaliações entraram, ~800 saíram, 69.809 presas em `EM_ANALISE` — **sem erro, sem latência
ruim, sem alerta**. `PipelineHealthMetrics` e `oldestPendingCreatedAt()` existem e nada
compara nada contra nada.

**O que torna isso útil é o baseline,** não o alerta. Limiar fixo não pega "o parceiro parou
de mandar" nem "a regra passou a aprovar tudo". O Origem normaliza por fração do dia decorrida
e janela comercial (`baseline.py`).

**Escopo desta entrada — os quatro de maior retorno, não os 19 do Origem:**

| Código | Dispara quando |
|---|---|
| `backlog_analise` | idade da avaliação mais antiga em `EM_ANALISE` acima do limiar (é o modo de falha já sofrido) |
| `vol_hora_baixo` | volume de intake abaixo do baseline da hora — parceiro que parou |
| `aprov_auto_alto` / `aprov_auto_baixo` | taxa de aprovação automática fora da faixa do baseline — regra que passou a aprovar tudo |
| `recusa_alta` | taxa de recusa acima do baseline |

**Arquivos.**
- Criar: pacote `com.barrier.riskengine.monitoring` — `Baseline` (média móvel normalizada por
  fração do dia e janela comercial), `AlertRule` (Strategy, um bean por código acima),
  `AlertEvaluator` (`@Scheduled`), `AlertNotifier` (interface; implementação de log por
  padrão, Slack atrás da interface — integração externa é `client`).
- Modificar: `PipelineHealthMetrics` — expor as contagens que o baseline consome, sem duplicar
  query.

**Regra de projeto:** `AlertRule` é Strategy pelo mesmo motivo que `RiskRule` é — adicionar
alerta = adicionar bean, sem tocar no avaliador. Flag `barrier.monitoring.alerts.enabled`.

**Dependência de F3:** `aprov_auto_*` e `recusa_alta` medem **desfecho**, que sai de
`assessments`; funcionam sem F3. `backlog_analise` também. F3 só é necessário se a fila
crescer para alertas sobre *nível corrente da base* (ex.: "salto de clientes em CRITICAL") —
fora do escopo desta entrada.

**Pronto quando:** teste que injeta série temporal sintética e verifica disparo/não-disparo de
cada regra; alerta ativo em `application-prod.yml`; o cenário do teste de carga (fila que para
de drenar) dispara `backlog_analise`.

---

## F6 — Política de reavaliação + `reassessment_decision` no trace (P4)

**Por quê.** `RescreeningService` tem três travas boas, mas todas contra *avalanche de
importação*. Não existe política sobre **quando reavaliar um cliente é legítimo**: PATCH de
cadastro, reintake, cliente tocado por duas fontes na mesma semana — tudo dispara ou nada
dispara, sem critério. E hoje um rescreening que **não** gerou avaliação é indistinguível de
um que nunca rodou.

**⚠️ A armadilha, e é a que mais fácil se erra.** Aplicar intervalo mínimo ao rescreening por
**watchlist** é regressão de compliance: entrada nova em lista de sanção é fato adverso novo,
e suprimi-la por "reavaliei há 30 dias" descumpre a Circular 3.978. Na matriz do Origem esse
caso é gatilho D, **bypass explícito**. O intervalo governa reavaliação *sem fato novo*
(periódica, cadastral). Custo de rescreening se controla por reuso de consulta (F1, entregue),
**nunca** por deixar de reavaliar.

**Modelo (três eixos independentes, do `adr-reanalise-gatilhos.md`):** gatilho + alteração
material + intervalo mínimo desde a última decisão, com intervalo por nível de risco. Mapeando
as faixas F1–F5 do Origem para as bandas do Barrier (lembrar: **escala invertida**, no Barrier
maior = pior):

| Banda Barrier | Dias |
|---|---|
| LOW | 1095 |
| MEDIUM | 730 |
| HIGH | 365 |
| CRITICAL | 183 |
| sem projeção (desconhecida) | 183 — fail-safe pelo pior caso |

**Arquivos.**
- Criar: `docs/adr/0019-politica-de-reavaliacao.md` — a matriz de gatilhos, quais exigem
  materialidade e quais fazem bypass. **O ADR vem antes do código nesta entrada.**
- Criar: pacote `com.barrier.riskengine.rescreening.policy` — `ReassessmentTrigger` (enum:
  `WATCHLIST_DELTA`, `PROFILE_PATCH`, `REINTAKE`, `ASSURANCE`, `PERIODIC`, `MANUAL`),
  `ReassessmentPolicy.decide(trigger, subject, tenant, materialChange)` devolvendo
  `ReassessmentDecision(reassess, reason)`.
- Criar: `V042__reassessment_decision.sql` — trilha de *por que não* reavaliou.
- Modificar: `RescreeningService` e `AssuranceReassessmentTrigger` — passam pela política
  antes de submeter. As travas atuais (linha de base, teto por importação, uma por
  subject/tenant por importação) e a janela de dedup do assurance (`PT5M`) **continuam**: são
  proteção de avalanche, ortogonais à política.

**Dependência de F3:** o intervalo é por nível de risco, e o nível corrente vem da projeção.

**Pronto quando:** teste prova que `WATCHLIST_DELTA` faz bypass do intervalo mesmo com
decisão de ontem; teste prova que `PERIODIC` respeita o intervalo por banda; toda decisão de
não reavaliar deixa linha com motivo.

---

## F7 — Mesa: fila nomeada, ações manuais e SLA pausável (P5)

**Por quê.** Já reconhecido no plano de remediação: *"Um `POST /decision` não é case
management: sem fila, SLA, atribuição, anexos, histórico"*.

**O detalhe que só a operação real ensina** — `sla_pausa_parceiro.py`: caso esperando
*documento do parceiro* não consome SLA, porque não é trabalho da mesa. Sem a pausa, o SLA
mede a lentidão do parceiro e culpa a mesa. E a postura que vem junto do código, que vale
copiar literalmente: *"Só contamos espera que dá para provar: sem registro de saída e fora da
fila, o intervalo é descartado."*

**Escopo.**
- Fila nomeada como propriedade da avaliação (`assessment_queue`) — no mínimo
  `analise_padrao`, `alcada_risco`, `aguardando_parceiro`.
- **Ações manuais como eventos, não só o desfecho final:** `assessment_actions` (atribuiu,
  moveu de fila, pediu documento, recebeu documento, decidiu). É a fonte a partir da qual a
  pausa é reconstruída — sem o histórico, não há o que descontar.
- SLA com pausa: intervalo em `aguardando_parceiro` com registro de entrada **e** saída não
  conta. Sem registro de saída, o intervalo é descartado (não conta como pausa — a regra é
  conservadora contra a mesa, de propósito).

**Dependência de F6:** o `SOLICITAR_DOCUMENTO` que já existe é exatamente o estado que alimenta
`aguardando_parceiro`, e a política de reavaliação define se o retorno do documento gera
avaliação nova ou retoma a mesma.

**Nota de escopo.** É a entrada mais cara da fila e a única que é produto novo, não hardening.
Vale confirmar apetite antes de abrir: EDD de verdade é o que P5 destrava, mas nada mais na
fila depende dela.

---

## F8 — Ingestão de evento comportamental (P7)

**Por quê.** Nada no Barrier reage a evento **pós-onboarding**. É a fundação do monitoramento
comportamental (item 7 da Fase 8 do `risk-engine-plan.md`), e o Origem já provou o desenho em
produção com o `tzofe`.

**⚠️ Importar o modelo de ingestão, NÃO o modelo de regra.** Regra como dado (`expr-lang` em
documento editável em runtime) sacrifica exatamente o que o Barrier faz de melhor:
`ENGINE_VERSION` e trilha reproduzível. **A recomendação da spec é não trocar o motor de
decisão por isso** — e ela vale como restrição desta entrada, não como opinião.

**O que importar, literalmente:**
- **Partição por documento** no Kafka → toda a atividade de uma entidade cai na mesma
  partição, preserva ordem e permite estado local sem coordenação.
- **Um consumer-group por consumidor** → consumidor lento não atrasa os outros.
- **Evento como fato imutável**, com campo de documento genérico (CPF, CNPJ ou id interno) — o
  barramento não precisa saber o que é a entidade.

**Dependência de F4:** F4 é o primeiro evento novo depois do `assessment.completed` e
estabelece a convenção de payload que F8 herda.

**Consequência:** F8 é o terceiro consumidor do barramento — **dispara F9**.

---

## F9 — Catálogo de eventos + schema registry (P8)

**Não é uma entrada de data, é uma entrada de gatilho.** Hoje `EventEnvelope` mora no
`commons` e é compartilhado por dependência Maven entre dois deployables. Funciona enquanto
forem dois. Com dois deployables e um tópico, registry é cerimônia.

**Acorda quando** (o que vier primeiro): terceiro consumidor de `barrier.assessment.completed`,
ou primeiro evento com payload que muda de forma. F8 satisfaz o primeiro.

**Entrega quando acordar.** `docs/architecture/event-catalog.md` no formato do catálogo do
Origem — é o formato a copiar, e o Barrier hoje não tem catálogo de eventos nenhum. Registry e
versionamento de payload entram na mesma leva, com a decisão de tecnologia em ADR próprio (o
Origem usa Avro + Schema Registry; a stack do Barrier é outra e a escolha não é automática).

---

## O que esta fila deliberadamente **não** contém

Da seção "o que **não** copiar" da spec — registrado aqui para que ninguém proponha como
melhoria em code review:

- média aritmética de sub-scores (diluição é propriedade da média, não bug);
- produto novo herdando o score vigente (gamificável);
- cap de recuperação amarrado ao score geral (deadlock);
- pesos do score composto do Mishmar (o bureau *é* a decisão; a biometria pesa zero);
- regra de negócio morando em Markdown (é o que `ENGINE_VERSION` existe para impedir);
- unificar a escala com a do Origem (lá maior = melhor, aqui maior = pior — inverter escala em
  migração é como se perde decisão histórica; se houver integração, documentar a conversão, não
  aplicá-la à base).
