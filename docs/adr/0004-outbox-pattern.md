# ADR-0004: Outbox pattern para publicação de eventos

- **Status:** Aceito
- **Data:** 2026-07-04

## Contexto

Cada serviço precisa gravar seu estado no PostgreSQL **e** publicar um evento no Kafka.
Fazer os dois em operações separadas (*dual-write*) permite falha parcial: gravar no banco
e não publicar (ou vice-versa), gerando divergência — inaceitável num domínio auditado.

## Decisão

Vamos usar o **outbox pattern**: na mesma transação de banco que altera o estado, o serviço
grava o evento numa tabela `outbox`. Um relay (poller ou CDC) lê a tabela e publica no
Kafka, marcando o evento como enviado.

## Alternativas consideradas

- **Dual-write direto (gravar + publicar sem outbox)** — sujeito a inconsistência em falha
  parcial. Descartado.
- **Transações distribuídas / 2PC entre banco e Kafka** — complexo, frágil e mal suportado.
  Descartado.

## Consequências

- **Positivas:** atomicidade "gravou = publicou"; sem perda nem evento fantasma; base para
  reprocessamento confiável.
- **Negativas / custos:** latência extra do relay; tabela `outbox` a manter e expurgar;
  entrega *at-least-once* (duplicados possíveis).
- **Mitigações:** consumidores idempotentes ([ADR-0003](0003-event-driven-kafka-choreography.md));
  expurgo periódico da `outbox` após confirmação de publicação.
