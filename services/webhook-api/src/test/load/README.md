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

A entrega é um POST **síncrono na thread do listener**. A vazão é, portanto,
`concorrência × (1 / latência do endpoint do cliente)` — e o cliente é quem determina a latência.
Um parceiro que responde em 100 ms derruba o serviço inteiro para 9 ev/s na configuração padrão
(1 partição, `concurrency=1`), e a fila de todos os outros tenants espera atrás dele: é bloqueio de
cabeça de fila, não lentidão distribuída. Com 8 partições e 8 consumidores o mesmo cenário sobe
para 55 ev/s.

Contexto: o `AssessmentProcessor` do risk-engine drenava ~12,5 avaliações/s na medição da
[ADR-0015](../../../../../docs/adr/0015-ingestao-em-massa-faixa-separada.md). Ou seja — o webhook
só vira gargalo quando o endpoint do cliente é lento, mas aí vira **com folga**.

O que isso indica como próximo passo (nenhum feito ainda):

- **Tópico com mais de uma partição.** Hoje nada no projeto cria o tópico com partições
  explícitas — ele nasce com 1 no auto-create do broker, e aí `spring.kafka.listener.concurrency`
  não tem o que paralelizar. Sem isso o resto não adianta.
- **Isolar o tenant lento.** Com a chave sendo o `assessmentId`, os eventos de um tenant se
  espalham por todas as partições — o parceiro lento contamina todas. Chavear por tenant confina o
  dano à partição dele.
- **Tirar o POST da thread do listener** (entregar por worker pool lendo de `deliveries`), que é o
  que remove o acoplamento entre latência do cliente e vazão de consumo de vez.

O `load.sink-latency-ms=0` (121 ev/s, ~8 ms por evento numa thread) mede o custo do próprio
serviço: insert + POST + update, dois round-trips ao Postgres por entrega.
