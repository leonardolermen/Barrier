# O que o Barrier tem a aprender com o ecossistema BMP Origem

Estudo comparativo entre o Barrier e a esteira de KYC que roda em **produção** na BMP —
`bmp-origem-back` (intake/cadastro), `Mishmar` (motor de risco), `bureaus-manager`
(adapter de bureaus), `tzofe` (eventos e reação) e `Sifria` (`kyc-events`, contrato
compartilhado).

**Por que este documento existe.** O Barrier é greenfield e decide melhor: motor de regras
plugável, trilha que distingue *rodou e passou* de *estava desligada*, `ENGINE_VERSION`
versionado, banda capada em REVIEW para não somar incertezas até virar certeza. Nada disso
existe do outro lado. Mas o Barrier **nunca viu tráfego real** — nenhum fornecedor caiu,
nenhuma fatura de bureau estourou, nenhum analista reclamou de fila. O Origem já pagou por
esse aprendizado, e boa parte dele está registrada em ADR. Este documento extrai o que vale
importar, o que não vale, e por quê.

**Regra de leitura:** cada item aponta o gap concreto no Barrier (com arquivo/linha quando
existe) e o mecanismo do lado de lá. Onde o item já está no
[plano de remediação](plano-remediacao-auditoria.md) ou no
[plano da Risk Engine](risk-engine-plan.md), o cruzamento está marcado — isto aqui não abre
frente nova, prioriza a que já existe.

---

## Panorama dos dois sistemas

| | **Barrier** | **Ecossistema Origem** |
|---|---|---|
| Deployables | `risk-engine`, `webhook-api` | `origem`, `mishmar`, `bureaus-manager`, `tzofe` (+ lib `kyc-events`) |
| Stack | Java 25 · Spring Boot 4 · Postgres · Kafka | Python 3.11 · FastAPI · MongoDB · Kafka + Schema Registry (Avro) · Go (tzofe) |
| Decisão de risco | Regras aditivas explicáveis, 0–1000 (**maior = pior**) | Média ponderada, 0–1000 (**maior = melhor**), faixas F1–F5 |
| Onde a regra mora | No código, versionada (`ENGINE_VERSION`) | Em Markdown (`risk-policy.md`), fora do código |
| Estado do cliente | Só `risk_scores` por avaliação | `score_corrente` (vivo) + `decisoes` (snapshot imutável) |
| Bureau | Cadeia dentro do `risk-engine`, sem cache | Serviço próprio, cache 24h Redis, payload bruto para auditoria |
| Pós-onboarding | Rescreening por delta de watchlist | Reanálise por gatilho + tzofe (skills reativas sobre eventos) |
| Mesa/EDD | `POST /decision` | Filas nomeadas, SLA com pausa, ações manuais rastreadas |
| Maturidade | 83 commits, 0 dias em prod | 332 commits (só o Origem), em produção |

---

## Prioridade 1 — Custo de bureau sem controle

**Gap.** `IdentityService.verify` sai para o bureau em **toda** avaliação. Não há reuso de
consulta recente. O [plano de remediação](plano-remediacao-auditoria.md) já mede a
exposição: R$0,04/consulta na BigBoost, 500 mil documentos = **R$20 mil** disparados por um
laço sobre o `POST` que ninguém aprovou. E o `RescreeningService` multiplica isso — uma
importação de watchlist que casa 400 subjects em 3 tenants cria 1.200 avaliações, cada uma
com sua consulta paga.

**Como o Origem resolve.** Duas camadas, e a distinção entre elas é o aprendizado:

1. **`bureaus-manager`** — serviço dedicado que cacheia respostas por **24h em Redis**, com
   escopo por adapter + contexto, devolve envelope normalizado e persiste o payload bruto no
   Mongo para auditoria. Trocar de provedor não impacta quem consome.
2. **TTL de reuso de decisão** ([`adr-derivacao-quadro-custos.md`](../../../Backend/bmp-origem-back/Docs/Cadastro%20Unico/adr-derivacao-quadro-custos.md)
   D3) — cadastro com decisão dentro do TTL cria vínculo e **não** chama análise nova. O
   primário é o intervalo mínimo por faixa de risco; o fallback é
   `DERIVACAO_REAPROVEITAR_DECISAO_HORAS=24`.

**O que importar.** Reuso de verificação de identidade recente por documento, com TTL
configurável e **proveniência registrada** — a trilha precisa dizer que aquele
`identity_check` foi reaproveitado, de qual consulta e de quando. Sem isso o barato sai
caro: a decisão passa a se apoiar em evidência que a trilha apresenta como fresca.

⚠️ **Escopo de reuso é decisão de produto, não de implementação.** O CLAUDE.md registra que
"cache compartilhado de dados objetivos entre tenants = futuro opt-in", e o
[ADR-0012](../adr/0012-subject-registration-profile.md) mostra por quê: cadastro
compartilhado vazava dossiê alheio. Reuso **dentro do mesmo tenant** é seguro e resolve a
maior parte do custo; reuso **entre tenants** exige ADR próprio.

→ **Entregue** (V040, `feat/identity-reuse`). O racional está no `CLAUDE.md`; o plano
bite-sized foi removido depois da entrega.

---

## Prioridade 2 — Não existe "risco corrente do cliente"

**Gap.** `risk_scores` guarda uma linha por avaliação. Para saber o risco atual de um subject
é preciso caçar a última avaliação concluída dele — e nada no código faz isso. Consequências:
não dá para responder "meus clientes em CRITICAL", não dá para avisar o parceiro quando o
risco muda, e o rescreening não sabe o que mudou em relação a antes.

**Como o Origem resolve.** O Mishmar mantém **três persistências com papéis distintos**:

| Collection | Papel |
|---|---|
| `kyc` | ciclo operacional (topo/substatus, bureaus consultados) |
| `decisoes` | snapshot **imutável** ao fechar (insert only) |
| `score_corrente` | score **vivo** por documento, atualizado a cada snapshot |

`GET /risk/v1/clientes/{documento}/score` lê o corrente com fallback ao último snapshot.

**O que importar.** Uma projeção `subject_risk_state` — nível corrente, decisão corrente,
avaliação que a produziu, `engine_version`, atualizada quando uma avaliação conclui. O
snapshot imutável o Barrier já tem (`risk_scores` nunca é sobrescrito); falta a projeção viva.

**Por que é prioridade 2 e não 1:** isto é o pré-requisito estrutural de três coisas —
webhook de mudança de nível (o produto vira assinatura, não consulta), reavaliação periódica
por nível de risco, e o item 6 abaixo. Nenhuma delas fecha sem ele.

---

## Prioridade 3 — Fila pode afogar em silêncio

**Gap.** O próprio teste de carga do Barrier registrou o pior modo de falha possível: 70.558
avaliações entraram, ~800 saíram, **69.809 presas em `EM_ANALISE`** — e *não houve erro, nem
latência ruim, nem alerta*. `PipelineHealthMetrics` e `oldestPendingCreatedAt()` existem, mas
nada compara nada contra nada.

**Como o Origem resolve.** `app/services/alertas/` — avaliador com **baseline móvel**
(`baseline.py`, 536 linhas) que normaliza por fração do dia decorrida e janela comercial, e
regras que disparam para Slack. Os códigos, que valem como checklist pronto:

`backlog_analise` · `backlog_mesa` · `backlog_mesa_crescendo` · `analise_stalled_alto` ·
`vol_hora_alto` · `vol_hora_baixo` · `vol_dia_anomalo` · `aprov_auto_alto` ·
`aprov_auto_baixo` · `recusa_alta` · `tempo_medio_alto` · `sla_mesa_atencao` ·
`sla_mesa_estourado` · `outbox_pendente` · `webhook_falhas` · `worker_nao_rodando` ·
`mishmar_offline` · `health_degradado` · `cadastros_sem_trace`

O que os torna úteis é o **baseline**: `vol_hora_baixo` e `aprov_auto_alto` pegam a falha
silenciosa — o parceiro que parou de mandar, a regra que passou a aprovar tudo. Alerta de
limiar fixo não pega nenhum dos dois.

**O que importar.** As regras de deriva de taxa (`aprov_auto_alto/baixo`, `recusa_alta`) e de
silêncio (`vol_hora_baixo`) são as de maior retorno, porque cobrem a classe de falha que o
Barrier já sofreu no teste de carga. Cruza com **"Métrica de idade da fila"** e
**"Observabilidade 2,5"** do plano de remediação.

---

## Prioridade 4 — Reavaliação sem política

**Gap.** O `RescreeningService` tem três travas boas (linha de base, teto por importação, uma
por subject/tenant), mas todas são contra *avalanche de importação*. Não há política sobre
**quando reavaliar um cliente é legítimo**: um PATCH de cadastro, um reintake, um cliente
tocado por duas fontes na mesma semana — tudo dispara ou nada dispara, sem critério.

**Como o Origem resolve.**
[`adr-reanalise-gatilhos.md`](../../../Backend/bmp-origem-back/Docs/Cadastro%20Unico/adr-reanalise-gatilhos.md)
— reanálise exige **gatilho** + **alteração material** + **intervalo mínimo desde a última
decisão**, com intervalo por faixa de risco:

| Faixa | Dias | Leitura |
|---|---|---|
| F1, F2 (risco baixo) | 1095 | cliente bom se reavalia a cada 3 anos |
| F3 | 730 | |
| F4 | 365 | |
| F5 (risco proibido) | 183 | cliente ruim se reavalia a cada 6 meses |
| desconhecida | 183 | fail-safe pelo pior caso |

Mais uma matriz de 9 gatilhos (A–I) dizendo quais exigem materialidade e quais fazem
**bypass** do intervalo.

⚠️ **Cuidado ao importar — e este é o ponto que mais fácil se erra.** Aplicar intervalo
mínimo ao rescreening por watchlist seria **regressão de compliance**: entrada nova em lista
de sanção é fato adverso novo sobre o cliente, e suprimi-la por "reavaliei há 30 dias"
descumpre a Circular 3.978. Na matriz do Origem esse caso é gatilho D — **bypass explícito**.
O intervalo governa reavaliação *sem fato novo* (periódica, cadastral); o custo do
rescreening se controla pelo item 1 (reuso de consulta), não por deixar de reavaliar.

**O que importar.** A separação entre gatilho, materialidade e intervalo — e o
`reanalise_decisao` no trace, que registra *por que não* reavaliou. Hoje, um rescreening que
não gerou avaliação é indistinguível de um que nunca rodou.

---

## Prioridade 5 — `POST /decision` não é mesa

**Gap.** Reconhecido no plano de remediação: *"Um `POST /decision` não é case management: sem
fila, SLA, atribuição, anexos, histórico"*.

**Como o Origem resolve.** Filas nomeadas como propriedade do cadastro
(`mesa_fila`), backfill para corrigir a coluna a partir das fontes que a guardam, e — o
detalhe que só a operação real ensina — **`sla_pausa_parceiro.py`**: caso na fila
`alcada_risco` está esperando *documento do parceiro*, e esse tempo **não consome SLA** porque
não é trabalho da mesa. O módulo reconstrói os intervalos de espera a partir das ações
manuais e desconta.

A frase que resume a postura, e que vale copiar junto do código: *"Só contamos espera que dá
para provar: sem registro de saída e fora da fila, o intervalo é descartado."*

**O que importar.** Fila nomeada + ações manuais como eventos (não só o desfecho final) +
SLA que sabe distinguir "a mesa está devagar" de "estamos esperando o cliente". Sem a pausa,
o SLA mede a lentidão do parceiro e culpa a mesa.

---

## Prioridade 6 — UBO vai custar caro do jeito planejado

**Gap.** [ADR-0016](../adr/0016-plataforma-completa-modelo-b.md) etapa 4 define "UBO até 3º
grau, com provider de relacionamentos atrás de interface". Não há **nenhuma** estratégia de
custo — e navegação societária é o caso mais explosivo que existe: cada nó da árvore é uma
consulta paga, e a árvore não tem tamanho conhecido de antemão.

**Como o Origem resolve.**
[`adr-derivacao-quadro-custos.md`](../../../Backend/bmp-origem-back/Docs/Cadastro%20Unico/adr-derivacao-quadro-custos.md)
— e o desenho é melhor que o do Barrier em três pontos:

- **Ordem por custo/benefício (D1):** dentro de cada PJ, CPFs de beneficiários **antes** dos
  sócios PJ — o mais barato e o mais provável de barrar cedo. Depois, profundidade primeiro:
  esgota a subárvore de um sócio antes do irmão.
- **Short-circuit com propagação ascendente (D2):** sócio reprovado marca **todos** os PJs da
  pilha como `RECUSADO` com motivo `socio_reprovado:<documento>`, e os sócios não analisados
  ficam `analise_suspensa` — **sem reconsulta de bureau**. A árvore para de custar no instante
  em que a resposta já está determinada.
- **Sem teto de profundidade.** A restrição declarada é não-negociável: "parada permitida só
  por reprovação, nunca por largura, profundidade ou participação". Cortar por profundidade
  seria mais barato e **erraria** — o beneficiário final costuma estar exatamente no fundo.

**O que importar.** As três restrições não-negociáveis, literalmente, para dentro do ADR de
UBO do Barrier antes de escrever o código. Elas são a diferença entre um UBO que fecha e um
que estoura a fatura ou mente.

---

## Prioridade 7 — Regra reativa como dado, não como deploy

**Gap.** O `risk_rule_registry` do Barrier liga/desliga uma família de regra e define vigência
— ótimo, e mais do que a maioria tem. Mas **criar** uma regra nova exige deploy, e não há
nada que reaja a evento pós-onboarding.

**Como o Origem resolve.** `tzofe` — plataforma Go onde uma *skill* é um documento no Mongo
com condição `expr-lang` e ação (tópico + template). O orchestrator reage a insert/update/delete
**em tempo real via change stream**. Três decisões que valem estudo independente da linguagem:

- **partição por `document`** no Kafka → toda a atividade de uma entidade cai na mesma
  partição, preserva ordem e permite que a skill mantenha estado local **sem coordenação**;
- **consumer-group por skill** → skill lenta não atrasa as outras;
- campo `document` genérico (CPF, CNPJ ou id interno) → o barramento não precisa saber o que
  é a entidade.

**O que importar — com ressalva.** Regra como dado sacrifica exatamente o que o Barrier faz
de melhor: `ENGINE_VERSION` e a trilha reproduzível. Uma expressão editável em runtime não é
auditável do mesmo jeito que uma `RiskRule` versionada. **A recomendação é não trocar** o
motor de decisão por isso. O que vale importar é o **modelo de ingestão**: partição por
documento, um consumer-group por consumidor, evento como fato imutável. É a fundação do
monitoramento comportamental (item 7 da Fase 8 do
[risk-engine-plan](risk-engine-plan.md)), e o Origem já provou que funciona.

---

## Prioridade 8 — Contrato de evento sem schema registry

**Gap.** `EventEnvelope` vive no `commons` e é compartilhado por dependência Maven entre os
dois deployables do Barrier. Funciona enquanto forem dois. Não há schema registry, nem
versionamento de payload, nem compatibilidade verificada.

**Como o Origem resolve.** `Sifria/kyc-events` é lib Python publicada no AWS CodeArtifact,
versionada (`kyc-events==0.1.16`), com pipeline própria que autodetecta bump e publica —
consumida por Origem, Mishmar e bureaus-manager. Os eventos trafegam em **Avro com Schema
Registry**.

**O que importar.** Ainda não. Com dois deployables e um tópico, registry é cerimônia. O item
fica registrado como **gatilho**: no terceiro consumidor do `barrier.assessment.completed`, ou
no primeiro evento com payload que muda de forma, o custo se inverte. O
[event-catalog.md](../../../Backend/bmp-origem-back/Docs/Cadastro%20Unico/event-catalog.md)
do Origem (304 linhas) é o formato a copiar quando chegar a hora — o Barrier hoje não tem
catálogo de eventos nenhum.

---

## Prioridade 9 — Quem re-enfileira o quê

**Gap.** O Barrier tem `DeliveryReconciliationJob`, recovery do processor por lease, e retry
do relay de outbox. Cada um faz a coisa certa **isoladamente**. Não há documento dizendo quem
é dono de qual recuperação — e é assim que dois mecanismos acabam recuperando a mesma coisa.

**Como o Origem resolve.**
[`adr-bureau-recovery-ownership.md`](../../../Backend/bmp-origem-back/Docs/Cadastro%20Unico/adr-bureau-recovery-ownership.md)
— 29 linhas, uma tabela de responsabilidade por estado, escrita **depois** de o
`bureau_pending_worker` do Origem ter duplicado solicitações ao bureau abrindo ciclo novo.
Custou dinheiro para aprender. A tabela termina com uma proibição explícita: *"Origem **não**
chama `run_analise` para `bureau_indisponivel`, `documento_nao_encontrado_bureau` nem
`aguardando_resposta_bureau` neste worker."*

**O que importar.** A tabela. É o item mais barato deste documento — uma página de ADR — e
previne uma classe de bug que o Barrier ainda não teve porque ainda não escalou.

---

## O que **não** copiar

Registrado com o mesmo cuidado que o resto, porque a tentação é copiar o modelo inteiro:

- **Média aritmética de sub-scores.** O
  [`risk-subscore-model.md`](../../../Backend/bmp-origem-back/Docs/Cadastro%20Unico/plans/risk-subscore-model.md)
  precisou de dois remendos contra a própria fórmula: "pior sub-score é floor do geral" e
  "produto dormido sai da média". Diluição é propriedade da média, não bug — e se resolve não
  usando média. O modelo aditivo com override do Barrier já é superior.
- **Produto novo herdando o score vigente.** Gamificável: fraudou no crédito, habilita três
  produtos, a média sobe.
- **Cap de recuperação amarrado ao score geral.** Cria deadlock — todos os produtos precisam
  se recuperar simultaneamente para qualquer um subir.
- **Pesos do score composto do Mishmar** (`scoreKyc * 0.10 + scoreBureau * 0.90 +
  scoreBiometria * 0.0`). Na prática o bureau *é* a decisão, a biometria não pesa nada, e o
  "score composto" descreve algo que não acontece. O Barrier não tem esse problema porque não
  tem peso: tem regra com evidência.
- **Regra de negócio morando em Markdown.** `risk-policy.md` e `risk-subscore-model.md` são a
  especificação real do motor, fora do código, sem teste e sem versão. É exatamente o que
  `ENGINE_VERSION` existe para impedir.
- **Escala invertida.** Origem/Mishmar: maior = melhor. Barrier: maior = pior. Não unificar —
  só documentar a conversão se algum dia houver integração, porque inverter escala em
  migração é como se perde decisão histórica.

---

## Sequência recomendada

Cada item entrega sozinho e habilita o seguinte. A ordem é por (risco que remove) ÷ (custo),
não por valor comercial.

| # | Item | Custo | Destrava |
|---|---|---|---|
| 1 | Reuso de verificação de identidade (P1) | baixo | corta a exposição de custo hoje |
| 2 | Ownership de recovery — ADR (P9) | ~1 página | previne duplicação antes de escalar |
| 3 | Projeção `subject_risk_state` (P2) | médio | webhook de mudança, reavaliação periódica |
| 4 | Alertas com baseline (P3) | médio | fecha "afoga em silêncio" |
| 5 | Política de reavaliação + trace (P4) | médio | governa o custo do rescreening |
| 6 | Restrições de custo no ADR de UBO (P6) | ~1 página | antes de escrever o código do UBO |
| 7 | Mesa com fila e SLA pausável (P5) | alto | EDD de verdade |
| 8 | Ingestão de evento comportamental (P7) | alto | monitoramento pós-onboarding |
| 9 | Catálogo de eventos + registry (P8) | — | só no gatilho descrito em P8 |

**Todos os nove itens foram entregues** — ver [fila-origem.md](fila-origem.md) (F1–F9) para o
escopo e o critério de pronto de cada um. O que vem agora está em
[plano-auditoria-2026-08-18.md](plano-auditoria-2026-08-18.md).
