# ADR-0003: Event-driven com Kafka (saga por coreografia)

- **Status:** Aceito
- **Data:** 2026-07-04

## Contexto

O fluxo de avaliação depende de integrações externas lentas e imprevisíveis (bureaus, OCR,
watchlists) e exige rastreabilidade e reprocessamento para fins regulatórios. Acoplar os
serviços por chamadas síncronas sofreria com latência e indisponibilidade em cascata.

## Decisão

Vamos comunicar os serviços por **eventos assíncronos via Apache Kafka**, coordenados por
**saga por coreografia** (cada serviço reage a eventos e emite os seus, sem orquestrador
central). Partição por `assessmentId` garante ordenação por avaliação.

## Alternativas consideradas

- **REST síncrono entre serviços** — simples, mas acopla e propaga falhas de bureaus lentos.
  Descartado.
- **Saga por orquestração (orquestrador central)** — controle de fluxo mais explícito, mas
  cria um ponto central que conhece todo o processo e vira gargalo de evolução. Descartado
  para o MVP; pode ser reconsiderado se o fluxo ficar muito ramificado.

## Consequências

- **Positivas:** desacoplamento; resiliência a integrações lentas; auditoria e
  reprocessamento naturais; escalabilidade por consumidor.
- **Negativas / custos:** consistência eventual; complexidade de depuração de fluxos
  distribuídos; necessidade de idempotência.
- **Mitigações:** `assessmentId` como correlation id em todos os eventos; idempotência por
  `assessmentId + eventType`; serviço de Audit consolidando a visão ponta a ponta.
