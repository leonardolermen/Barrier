# Fluxo de eventos e saga

O fluxo de avaliação é uma **saga por coreografia**: cada serviço reage a eventos e emite
os seus, sem orquestrador central.

## Passo a passo de uma avaliação

1. Cliente faz `POST /assessments` com os dados do cliente final.
2. **Assessment API** persiste o pedido, responde **`202 { id, status: em_analise }`** na
   hora e grava `assessment.requested` na tabela `outbox` (mesma transação).
3. O relay de outbox publica `assessment.requested` no Kafka.
4. **Identity** e **Screening** consomem em paralelo e emitem `identity.verified` /
   `identity.failed` e `screening.completed` (com eventuais hits).
5. **Risk scoring** aguarda identidade + screening, calcula o nível e emite `risk.scored`
   (`baixo` | `medio` | `alto`).
6. Se `alto` **ou** houve hit de sanção/PEP → **Case management** entra (fila do analista,
   EDD) e emite `case.decided`. Se `baixo` e sem hit, pula esta etapa.
7. **Assessment API** agrega o resultado final e emite `assessment.completed`.
8. **Webhook dispatcher** faz o callback no endpoint do cliente (retry + assinatura HMAC).
   O cliente também pode consultar `GET /assessments/{id}` a qualquer momento.
9. **Audit** consome todos os eventos em paralelo e grava a trilha imutável.

## Sequência

```
Cliente        Assessment API      Kafka        Identity/Screening   Risk   Case   Webhook
  │  POST            │                │                 │             │      │        │
  ├─────────────────▶│                │                 │             │      │        │
  │  202 em_analise  │                │                 │             │      │        │
  │◀─────────────────┤                │                 │             │      │        │
  │                  ├─assessment.requested─▶│           │             │      │        │
  │                  │                │──────────────────▶│            │      │        │
  │                  │                │◀─identity.verified┤            │      │        │
  │                  │                │◀─screening.completed           │      │        │
  │                  │                │───────────────────────────────▶│     │        │
  │                  │                │◀────────────────────risk.scored┤     │        │
  │                  │                │  (se alto/hit)──────────────────────▶│        │
  │                  │                │◀──────────────────────────case.decided┤        │
  │                  │◀─agrega─────────                                       │        │
  │                  ├─assessment.completed─▶│──────────────────────────────────────▶│
  │◀────────────────────────────── webhook callback ──────────────────────────────────┤
```

## Contratos de evento (nomes canônicos)

| Evento                  | Emitido por     | Consumido por                    |
|-------------------------|-----------------|----------------------------------|
| `assessment.requested`  | Assessment API  | Identity, Screening, Audit       |
| `identity.verified`     | Identity        | Risk, Audit                      |
| `identity.failed`       | Identity        | Assessment API, Audit            |
| `screening.completed`   | Screening       | Risk, Audit                      |
| `risk.scored`           | Risk scoring    | Case mgmt, Assessment API, Audit |
| `case.decided`          | Case management | Assessment API, Audit            |
| `assessment.completed`  | Assessment API  | Webhook dispatcher, Audit        |

Todos os eventos carregam: `assessmentId` (correlation id), `eventId` (idempotência),
`occurredAt`, `version` e `payload`.

## Garantias

- **Outbox pattern** — atomicidade entre gravação no banco e publicação no Kafka.
- **Idempotência** — chave `assessmentId + eventType`; consumidores descartam duplicados.
- **At-least-once** — Kafka pode reentregar; nenhum consumidor assume exactly-once.
- **Ordenação** — partição por `assessmentId` garante ordem por avaliação.
