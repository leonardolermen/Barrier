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

## Topologia (MVP — modelo motor de risco)

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

Ver diagrama detalhado em [event-flow.md](event-flow.md).

## Serviços do MVP

| Serviço              | Papel                                                            |
|----------------------|------------------------------------------------------------------|
| **Assessment API**   | Borda. Intake, resposta 202, consulta de status, agregação final |
| **Identity**         | Validação CPF/CNPJ, cruzamento com bureaus                       |
| **Screening**        | Match PEP, sanções (ONU/OFAC/CGU), mídia adversa                 |
| **Risk scoring**     | Classificação de risco (baixo/médio/alto)                        |
| **Case management**  | Análise manual / EDD, fila de analistas                          |
| **Webhook dispatcher** | Callback assíncrono ao cliente com retry e assinatura          |
| **Audit & compliance** | Trilha imutável, retenção, evidência regulatória              |

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
