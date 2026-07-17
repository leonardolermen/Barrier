# Fluxo de eventos e saga

## Como está implementado hoje

O núcleo de decisão é **em processo** dentro da Risk Engine (os módulos identity → screening
→ risk conversam por chamada de método, não por Kafka). O **único evento publicado** é
`barrier.assessment.completed`, consumido pela **Webhook API**.

### Passo a passo de uma avaliação

1. Cliente faz `POST /v1/assessments` com os dados do cliente final.
2. **assessment** (módulo) persiste em `EM_ANALISE` e responde **`202 { id, status:
   EM_ANALISE }`** na hora.
3. Um processador assíncrono (`@Scheduled`) pega os pendentes e, para cada um:
   1. **identity** — valida CPF/CNPJ no bureau (BrasilAPI/BigBoost reais, com fallback em
      cadeia; stub só em dev/teste) → `IdentityCheck` (+ `CompanyProfile` de PJ).
   2. **screening** — busca PEP/sanções (CGU/OFAC reais, gated por config) e mídia negativa
      (stub) e aplica as regras → `ScreeningResult`.
   3. **risk** — o motor roda as regras ativas (Strategy, filtradas pelo *rule registry*),
      soma um **score 0–1000**, deriva o nível (LOW/MEDIUM/HIGH/CRITICAL) e a recomendação
      (APPROVE/REVIEW/REJECT) → `RiskDecision`. As regras consomem identidade, screening,
      cadastro, sinais de rede (GeoIP/device/telefone/email) e histórico interno.
   4. A recomendação vira o status (APROVADO/EM_REVISAO/REPROVADO); se o cadastro (CMN
      4.753) estiver incompleto, `APROVADO` é rebaixado para `EM_REVISAO`. Os fatores
      explicáveis ficam gravados na avaliação.
4. Na **mesma transação** da conclusão, grava `barrier.assessment.completed` na `outbox`.
5. O **relay de outbox** (`@Scheduled`) publica o evento no Kafka.
6. A **Webhook API** consome o evento, monta o corpo, assina com **HMAC** e faz `POST` no
   endpoint do cliente; registra a entrega em `deliveries` (idempotência por `eventId`,
   retry com backoff). O cliente também pode consultar `GET /v1/assessments/{id}`.

Revisão manual (EDD): uma avaliação em `EM_REVISAO` é decidida por humano via
`POST /v1/assessments/{id}/decision`, que reemite `barrier.assessment.completed` com o
desfecho final — o mesmo contrato de evento, só que disparado pela decisão humana em vez do
processador automático.

### Sequência

```
Cliente        Risk Engine (assessment→identity→screening→risk)   Kafka        Webhook API
  │  POST            │                                              │              │
  ├─────────────────▶│                                              │              │
  │  202 EM_ANALISE  │                                              │              │
  │◀─────────────────┤                                              │              │
  │                  │ processa (em processo) → decisão             │              │
  │                  ├─ outbox → assessment.completed ─────────────▶│              │
  │                  │                                              │─ consome ───▶│
  │◀──────────────────────────── callback assinado (HMAC) ────────────────────────┤
```

### Contrato de evento

| Evento                        | Emitido por | Consumido por |
|-------------------------------|-------------|---------------|
| `barrier.assessment.completed`| Risk Engine | Webhook API   |

Envelope (`EventEnvelope` em `commons`): `eventId` (idempotência), `type`, `assessmentId`
(correlation id + chave de partição no Kafka), `occurredAt`, `version`, `payload`.

### Garantias

- **Outbox pattern** — atomicidade entre gravação no banco e publicação no Kafka.
- **Idempotência** — a Webhook API desduplica por `eventId` (UNIQUE em `deliveries`).
- **At-least-once** — Kafka pode reentregar; nenhum consumidor assume exactly-once.
- **Ordenação** — partição por `assessmentId` garante ordem por avaliação.

## Visão futura — saga por coreografia

Quando os módulos forem extraídos em serviços próprios (escala), o fluxo em processo vira
uma **saga por coreografia**, com eventos intermediários entre os contextos:

| Evento                  | Emitido por     | Consumido por                    |
|-------------------------|-----------------|----------------------------------|
| `assessment.requested`  | Assessment      | Identity, Screening, Audit       |
| `identity.verified`     | Identity        | Risk, Audit                      |
| `screening.completed`   | Screening       | Risk, Audit                      |
| `risk.scored`           | Risk scoring    | Case mgmt, Assessment, Audit     |
| `case.decided`          | Case management | Assessment, Audit                |
| `assessment.completed`  | Assessment      | Webhook API, Audit               |

Os contratos de evento já vivem no módulo `commons` para facilitar essa extração — ver
[ADR-0009](../adr/0009-risk-engine-modular-monolith-first.md).
