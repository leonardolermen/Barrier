# Teste de carga — webhook-api

Diferente do arnês de [k6 do risk-engine](../../../../risk-engine/src/test/load/README.md), aqui a
carga **não entra por HTTP**: o webhook-api não tem endpoint de entrada, só o listener de
`barrier.assessment.completed`. O gerador de carga é um teste JUnit —
[`WebhookLoadTest`](../java/com/barrier/webhook/WebhookLoadTest.java) — que sobe Kafka + Postgres
com Testcontainers, publica N eventos e mede o caminho todo até o POST chegar num endpoint de
cliente simulado.

Ele fica fora do `./mvnw test` pela tag `load` (`excludedGroups` no pom raiz).

## Rodar

```bash
./mvnw -pl services/webhook-api test -Dgroups=load -DexcludedGroups= -Dtest=WebhookLoadTest -Dload.events=1000 -Dload.sink-latency-ms=100 -Dload.partitions=8 -Dload.concurrency=8
```

| propriedade | default | o que é |
| --- | --- | --- |
| `load.events` | 1000 | eventos publicados no tópico |
| `load.sink-latency-ms` | 0 | quanto o endpoint do cliente demora para responder 200 |
| `load.partitions` | 1 | partições do tópico |
| `load.concurrency` | 1 | `spring.kafka.listener.concurrency` |
| `load.timeout-minutes` | 10 | teto de espera pela última entrega |

As asserções são de **correção sob carga** (todas entregues, nenhum POST duplicado, uma linha por
evento em `deliveries`), não de performance: número vira meta depois de medido em ambiente fixo,
e threshold em máquina de dev só produz build vermelho aleatório.

## Medição de 2026-08-10 (dev, Docker Desktop/Windows)

| eventos | latência do sink | partições/concorrência | vazão | e2e p95 |
| --- | --- | --- | --- | --- |
| 1000 | 0 ms | 1 / 1 | **121 ev/s** | 7,4 s |
| 500 | 100 ms | 1 / 1 | **9 ev/s** | 52,6 s |
| 500 | 100 ms | 8 / 8 | **55 ev/s** | 7,2 s |

## Como ler isso

**Medição de 2026-08-10, com a entrega ainda como POST síncrono na thread do listener** (estado
já corrigido — ver os itens marcados "feito" abaixo). Na configuração de então, a vazão era
`concorrência × (1 / latência do endpoint do cliente)` — e o cliente determinava a latência. Um
parceiro que respondia em 100 ms derrubava o serviço inteiro para 9 ev/s na configuração padrão
(1 partição, `concurrency=1`), e a fila de todos os outros tenants esperava atrás dele: bloqueio de
cabeça de fila, não lentidão distribuída. Com 8 partições e 8 consumidores o mesmo cenário subia
para 55 ev/s. Os números seguem válidos como registro histórico; a causa que eles diagnosticavam
foi endereçada (abaixo).

Contexto: o `AssessmentProcessor` do risk-engine drenava ~12,5 avaliações/s na medição da
[ADR-0015](../../../../../docs/adr/0015-ingestao-em-massa-faixa-separada.md). Ou seja — o webhook
só vira gargalo quando o endpoint do cliente é lento, mas aí vira **com folga**.

O que a medição indicava como próximo passo, e o que aconteceu com cada item desde então:

- **Tópico com mais de uma partição — feito.** `KafkaTopicsConfig` cria os três tópicos do
  barramento com partições explícitas (default 6, ≥ réplicas alvo), provado por
  `KafkaTopicCreationIntegrationTest` contra o broker.
- **Tirar o POST da thread do listener — feito.** `WebhookDeliveryService.onEvent` (chamado pelo
  listener) só registra a entrega em `deliveries`; quem entrega é `retryDue()` (agendado), por um
  `Executors.newVirtualThreadPerTaskExecutor()` com `Semaphore` limitando concorrência. A latência
  do parceiro deixou de existir no caminho do broker.
- **Isolar o tenant lento — em aberto.** A chave de partição continua sendo o `assessmentId`
  (`KafkaEventPublisher`); os eventos de um tenant ainda se espalham por todas as partições, e um
  parceiro lento ainda contamina todas. Chavear por tenant confinaria o dano à partição dele —
  não feito.

O `load.sink-latency-ms=0` (121 ev/s, ~8 ms por evento numa thread) mede o custo do próprio
serviço: insert + POST + update, dois round-trips ao Postgres por entrega.
