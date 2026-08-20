# Catálogo de eventos

Contrato de todos os eventos que trafegam no barramento do Barrier. **Este documento é normativo:**
evento que não está aqui não deveria existir, e mudança de forma de payload que não passa por aqui
quebra consumidor sem aviso.

**Por que ele existe agora.** O gatilho declarado na [prioridade 8 das lições do Origem][licoes] era
*"o terceiro consumidor do barramento, ou o primeiro evento com payload que muda de forma"*. Com
`barrier.behavior.recorded` (F8), o barramento passou de um tópico para três, e a partir daqui o
custo de não ter catálogo é maior que o de mantê-lo. O formato copia o
[`event-catalog.md` do Origem][origem], que já sustenta quatro serviços em produção.

## Regras que valem para todo evento

- **Publicação só por transactional outbox.** Nunca `kafkaTemplate.send` direto de um service.
- **Consumo idempotente por `eventId`.** Kafka é at-least-once; consumidor que não deduplica está
  errado, não azarado.
- **Um consumer-group por consumidor**, nunca por tópico. Consumidor lento não pode atrasar os
  outros — é a lição do `tzofe`.
- **Envelope comum** (`EventEnvelope`, no `commons`): `eventId`, `type`, `assessmentId`
  (id do agregado, ver ressalva abaixo), `occurredAt`, `version`, `payload`, `correlationId`.
- **Nunca dado pessoal na chave de partição.** Chave aparece em log de broker, métrica de lag e
  ferramenta de inspeção — lugares sem o controle de acesso do banco.
- **Documento no payload vai mascarado.**
- **Campo novo é retrocompatível; campo removido ou com semântica trocada não é** — ver
  "Mudança de contrato".

⚠️ **Ressalva do envelope.** O campo do agregado chama-se `assessmentId` por origem histórica (o
primeiro evento era de avaliação) e hoje carrega o id do agregado de cada evento — que nem sempre é
uma avaliação. Renomear é mudança de forma em todos os consumidores ao mesmo tempo; enquanto não
houver motivo maior, a coluna `deliveries.assessment_id` da Webhook API depende desse nome. Está
registrado aqui para ninguém interpretar o campo pelo nome.

---

## `barrier.assessment.completed`

| | |
|---|---|
| **Versão** | 1 |
| **Produtor** | `risk-engine` — `AssessmentEventPublisher` |
| **Chave de partição** | `assessmentId` |
| **Consumidores** | `webhook-api` (`AssessmentCompletedListener`), `DeliveryReconciliationJob` (releitura avulsa, sem group) |
| **Quando** | avaliação atinge desfecho, pelo motor ou por decisão humana. **Reemitido** na decisão manual |

Payload: `AssessmentCompletedPayload` — `assessmentId`, `tenantId`, `subjectId`, `status`,
`riskLevel`, `decision`, `completedAt`, `identityReused`, `identityCheckedAt`.

⚠️ **Corrigido em 2026-08-19:** este catálogo listava `factors`, `documentType`, documento
mascarado, `origin` e `originDetail`, que **nunca estiveram no payload** — o parceiro que os
esperasse receberia `null` sem explicação. É o modo de falha que o próprio catálogo existe para
evitar, e a razão de ele precisar ser atualizado no mesmo PR que muda o evento.

**`subjectId` é a chave de ordenação da entrega.** Duas entregas do mesmo subject nunca saem em
paralelo; de subjects diferentes, sim — sem ele, a decisão e a mudança de nível de risco do mesmo
cliente teriam chaves distintas e poderiam chegar fora de ordem.

**Reemissão é esperada:** o mesmo `assessmentId` gera um evento na conclusão automática e outro na
decisão do analista, com `eventId` diferentes. Consumidor que trata "já vi esta avaliação" como
"posso ignorar" perde o desfecho final.

---

## `barrier.subject.risk_level_changed`

| | |
|---|---|
| **Versão** | 1 |
| **Produtor** | `risk-engine` — `RiskLevelChangeEventPublisher` |
| **Chave de partição** | `assessmentId` (a avaliação que causou a mudança) |
| **Consumidores** | `webhook-api`, no **mesmo** listener e group do desfecho |
| **Quando** | o nível corrente do cliente muda de faixa. **Não** emite na primeira avaliação nem em nível repetido |

Payload: `RiskLevelChangedPayload` — `tenantId`, `subjectId`, `documentType`, documento
**mascarado**, `previousLevel`, `currentLevel`, `worsened`, `decision`, `assessmentId`, `origin`,
`engineVersion`, `changedAt`.

**Sem reconciliação, deliberadamente** ([ADR-0017](../adr/0017-ownership-de-recovery.md)): é aviso
sobre estado consultável (`GET /v1/subjects/{doc}/risk-state`), não o registro único de um fato.

---

## `barrier.behavior.recorded`

| | |
|---|---|
| **Versão** | 1 |
| **Produtor** | `risk-engine` — `BehaviorEventPublisher` |
| **Chave de partição** | **`subjectId`** |
| **Consumidores** | nenhum ainda — o acervo é a fundação; as regras comportamentais são entrega própria |
| **Quando** | fato comportamental é ingerido e gravado (não em reenvio duplicado) |

Payload: `BehaviorRecordedPayload` — `tenantId`, `subjectId`, `eventId`, `eventType`, `occurredAt`,
`receivedAt`.

**Partição por `subjectId` é a lição do `tzofe` adaptada.** Toda a atividade de um cliente cai na
mesma partição: preserva a ordem dos fatos dele e permite estado local no consumidor sem
coordenação. O Origem usa o `document` (CPF/CNPJ); aqui não, porque documento em chave de partição
espalharia dado pessoal pela malha de observabilidade — e o `subjectId` é único por documento
(ADR-0011), dando a mesma garantia.

**O payload do parceiro não trafega.** O conteúdo livre que ele mandou fica no acervo; o evento
anuncia que o fato existe. Quem precisar do conteúdo lê a base, com o controle de acesso dela.

---

## Mudança de contrato

**Retrocompatível** (não sobe `version`, não precisa de coordenação): adicionar campo opcional.
Consumidores do Barrier desserializam de forma tolerante, e a Webhook API repassa o payload como
string opaca.

**Quebra** (sobe `version`, exige coordenação): remover campo, renomear, mudar tipo, mudar a
semântica de um valor existente. O procedimento é publicar as duas versões em paralelo até todo
consumidor migrar — e é aqui que a falta de um schema registry dói.

### Sobre o schema registry

**Ainda não há**, e a decisão é consciente. O ecossistema Origem usa Avro + Confluent Schema
Registry, com a lib `kyc-events` versionada no CodeArtifact; o Barrier tem dois deployables, três
tópicos, um único produtor e o envelope compartilhado por dependência Maven do `commons` — que já
dá compatibilidade em tempo de compilação para tudo que é interno.

O registry passa a valer a pena quando **o produtor deixar de ser único** (consumidor externo
publicando no barramento) ou quando a **primeira quebra real** precisar ser coordenada entre times
que não compilam juntos. Até lá seria cerimônia: infraestrutura nova, formato binário e um passo a
mais no build para resolver um problema que o monorepo resolve.

**Este catálogo é a mitigação enquanto isso** — e a condição para ela funcionar é que ele seja
atualizado no mesmo PR que muda o evento. Catálogo desatualizado é pior que catálogo nenhum: dá
confiança falsa.

[licoes]: ../implementation/licoes-do-origem.md
[origem]: ../../../Backend/bmp-origem-back/Docs/Cadastro%20Unico/event-catalog.md
