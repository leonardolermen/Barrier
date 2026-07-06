# Visão geral da arquitetura

## Princípios

1. **Borda não bloqueia.** A API responde na hora (`202 em_analise`) e joga o trabalho
   pesado (bureaus, OCR, watchlists) para processamento assíncrono via Kafka.
2. **Coreografia, não orquestração central.** Cada serviço reage a eventos e emite os
   seus. Não há um orquestrador único que conhece o fluxo inteiro.
3. **Auditoria é cidadã de primeira classe.** Todo evento é imutável e retido. A trilha
   de auditoria é requisito regulatório, não feature opcional.
4. **Camadas clássicas por serviço.** Cada microserviço usa `controller → service →
   repository`. Integrações externas ficam atrás de uma interface no pacote `client`.
5. **Fase 1 ⊂ Fase 2.** O motor de risco de hoje é subconjunto da plataforma completa
   de amanhã; a evolução adiciona módulos, não reescreve.

## Corte atual — Risk Engine API

O primeiro corte **não** são os 6 microserviços de uma vez. Começamos por uma única
**Risk Engine API** (um deployable) que encapsula Identity, Screening e Risk scoring como
módulos internos em camadas clássicas, conversando por chamada de método em processo — ver
[ADR-0009](../adr/0009-risk-engine-modular-monolith-first.md).

```
sistema do cliente ──POST /assessments──▶ Risk Engine API ──202 {id, em_analise}──▶ cliente
                                               │  (GET /assessments/{id} p/ status)
                                               │  identity → screening → risk → decisão
                                               ▼ (outbox)
                                        ┌──────────────┐
                                        │    Kafka     │  assessment.completed
                                        └──────────────┘
                                               │
                                               ▼  (fase seguinte)
                                        Webhook API  ── callback ──▶ cliente
```

A **Webhook API** entra depois como deployable separado, consumindo `assessment.completed`.

## Topologia-alvo (visão de longo prazo — microserviços)

A visão abaixo é o **destino**, não o corte inicial. O núcleo de risco será dividido
incrementalmente quando a escala exigir.

![Topologia de microserviços do MVP](../diagrams/topologia-mvp.svg)

```
sistema do cliente ──POST──▶ Assessment API ──202 síncrono──▶ sistema do cliente
                                   │
                                   ▼ (outbox)
                          ┌──────────────────┐
                          │  Kafka (eventos) │
                          └──────────────────┘
             ┌──────────┬──────────┼──────────┬─────────────┐
             ▼          ▼          ▼           ▼             ▼
         Identity   Screening   Risk       Case mgmt    Webhook
                                scoring                  dispatcher
                                   │
                          ┌──────────────────┐
                          │ Audit & compliance│  (consome todos os eventos)
                          └──────────────────┘
```

Ver diagrama detalhado em [event-flow.md](event-flow.md) e o SVG em
[docs/diagrams/topologia-mvp.svg](../diagrams/topologia-mvp.svg).

## Serviços da topologia-alvo

Papéis na visão de longo prazo. No corte atual, os três primeiros são **módulos internos**
da Risk Engine API; os demais entram nas fases seguintes.

| Serviço / módulo     | Papel                                                            | Onde vive agora            |
|----------------------|------------------------------------------------------------------|----------------------------|
| **Assessment**       | Borda. Intake, resposta 202, consulta de status, agregação final | módulo da Risk Engine API  |
| **Identity**         | Validação CPF/CNPJ, cruzamento com bureaus                       | módulo da Risk Engine API  |
| **Screening**        | Match PEP, sanções (ONU/OFAC/CGU), mídia adversa                 | módulo da Risk Engine API  |
| **Risk scoring**     | Classificação de risco (baixo/médio/alto)                        | módulo da Risk Engine API  |
| **Webhook API**      | Callback assíncrono ao cliente com retry e assinatura           | próxima fase (deployable)  |
| **Case management**  | Análise manual / EDD, fila de analistas                          | fase 2                     |
| **Audit & compliance** | Trilha imutável, retenção, evidência regulatória              | fase 2                     |

## Estilo interno de cada serviço (camadas clássicas)

```
com.kyc.<contexto>
├── controller     REST (@RestController) + listeners Kafka (@KafkaListener)
├── service        regra de negócio
├── repository     JPA (@Repository)
├── domain/model   entidades e enums
├── dto            request/response + contratos de evento
├── client         integrações externas atrás de interface (ex.: WatchlistProvider)
└── config         Kafka, security, beans
```

Disciplina única obrigatória: o `service` acessa integrações externas **por interface**
(pacote `client`), nunca pelo SDK direto. Custo baixo, ganho alto para teste e auditoria.

O que preenche cada camada em todos os serviços do MVP:

![Arquitetura em camadas por serviço](../diagrams/camadas-por-servico.svg)

## Padrões transversais

- **Outbox pattern** — evento gravado na mesma transação do banco; relay publica no Kafka.
  Garante "gravou = publicou", sem *dual-write*.
- **Idempotência** — consumidores tratam entrega repetida (Kafka é *at-least-once*).
  Chave: `assessmentId + eventType`.
- **Correlação** — `assessmentId` viaja em todos os eventos como *correlation id*,
  alimentando auditoria e *tracing*.

## Infraestrutura de desenvolvimento

- Monorepo Gradle multi-módulo (um módulo por serviço + módulo `commons` de eventos).
- `docker-compose` local sobe Kafka + PostgreSQL.

> Decisão de monorepo ainda em ADR proposto — ver [ADR-0008](../adr/0008-monorepo-gradle.md).
