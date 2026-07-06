# ADR-0009: Risk Engine como monólito modular primeiro, split incremental

- **Status:** Aceito
- **Data:** 2026-07-06
- **Substitui:** parcialmente o [ADR-0001](0001-microservices-topology.md)

## Contexto

O [ADR-0001](0001-microservices-topology.md) definiu microserviços desde o dia 1. Ao
detalhar o primeiro corte, ficou claro que os contextos do núcleo de decisão de risco
(Identity, Screening, Risk scoring) são fortemente coesos e sempre executados na mesma
avaliação. Separá-los em serviços desde já traz custo de consistência distribuída e infra
sem benefício correspondente nesta fase.

O modelo de produto ([ADR-0005](0005-product-model-risk-engine.md)) é justamente "motor de
risco (A) evoluindo para plataforma (B)", o que favorece começar enxuto e dividir quando a
escala pedir.

## Decisão

Vamos construir uma única **Risk Engine API** (um deployable) que encapsula todo o núcleo
de decisão de risco como **módulos internos em camadas clássicas**:

```
Risk Engine API  (com.barrier.riskengine)
├── assessment/   orquestra o fluxo, expõe o REST, agrega a decisão
├── identity/     valida CPF/CNPJ · client BureauProvider
├── screening/    match PEP/sanções · client WatchlistProvider
├── risk/         calcula score baixo/médio/alto
├── shared/       contratos de evento, outbox, correlação
└── config/       Kafka (saída), security, beans
```

- Os módulos conversam por **chamada de método em processo**, não por Kafka.
- Contrato de retorno: `POST /assessments` responde **`202 { id, status: em_analise }`**;
  o cliente consulta **`GET /assessments/{id}`** (polling) — ver
  [ADR-0006](0006-sync-and-webhook-response.md).
- Ao concluir, a Risk Engine **emite `assessment.completed` no Kafka via outbox**
  ([ADR-0004](0004-outbox-pattern.md)), deixando pronto o gancho para a Webhook API.

### Evolução planejada

1. **Agora:** Risk Engine API (monólito modular).
2. **Depois:** **Webhook API** — deployable separado que consome `assessment.completed` do
   Kafka e faz os callbacks (retry, backoff, HMAC, idempotência de entrega). Entrega de
   webhook é problema de infraestrutura, ortogonal à regra de risco.
3. **Fase 2:** Case management, Audit e, se a escala exigir, extração de Identity/Screening
   em serviços próprios.

## Alternativas consideradas

- **6 microserviços desde já (ADR-0001 original)** — maior isolamento, mas custo de infra e
  consistência distribuída sem retorno nesta fase. Adiado.
- **Fatia vertical fina com Kafka interno entre módulos** — bom para exercitar o
  event-driven cedo, mas adiciona complexidade ao núcleo coeso sem necessidade. Descartado
  em favor de chamadas em processo; o Kafka fica só na fronteira de saída.

## Consequências

- **Positivas:** time-to-market muito menor; núcleo coeso fácil de testar; contrato de
  evento e outbox já no lugar para a Webhook API; caminho de split preservado.
- **Negativas / custos:** um deployable concentra Identity/Screening/Risk; escalam juntos
  por enquanto.
- **Mitigações:** manter fronteiras de módulo limpas (pacotes por contexto, integrações
  externas atrás de interface no `client`) para que a extração futura seja mecânica.
