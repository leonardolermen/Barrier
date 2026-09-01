# Paralelismo do pipeline: processamento de avaliações e entrega de webhook

- **Data:** 2026-08-19
- **Origem:** item P2 do [plano-auditoria-2026-08-18.md](../../implementation/archive/plano-auditoria-2026-08-18.md)
  ("Paralelizar os três loops sequenciais"), confirmado por medição própria.

## O problema, medido

Teste de carga em uma instância (k6, rampa até 150 VUs, ~5 min):

| | |
|---|---|
| Ingestão | **148 req/s**, 0% de erro em 43.176 requisições |
| Processamento | **6 avaliações/s** (medido depois do k6 parar, sem carga competindo) |
| Fila ao fim | **94.222** em `EM_ANALISE`, mais antiga com 401s |

A aritmética explica o 6/s sem mistério: `BATCH = 50` processadas **uma a uma** numa thread, com
`fixedDelay` de 2s entre lotes. São ~126ms por avaliação — e isso com o **bureau simulado**, que
não toca a rede. Com bureau real (RTT de 100–300ms) a taxa cairia para 2–3/s.

**Isto reproduz o modo de falha do ADR-0015** (69.809 avaliações presas sem erro, sem latência ruim
e sem alerta), agora com 94.222.

**Não é código ruim — é código sequencial.** Os 126ms são trabalho real (identidade, screening, 12
regras, trilha, outbox). O que falta é que nada disso roda em paralelo, apesar de o mecanismo que
permite paralelizar já existir e estar correto: `claimPending` usa `FOR UPDATE SKIP LOCKED` + lease,
que é exatamente o que N workers precisam para pegar conjuntos disjuntos.

## Decisões que moldam o desenho

Tomadas explicitamente antes do desenho:

| Decisão | Escolha | Por quê |
|---|---|---|
| Ordem no webhook | **por subject** | Preserva o que importa (não avisar "virou HIGH" antes de "virou MEDIUM") sem serializar tudo. Ordem por tenant limitaria o parceiro grande a uma entrega por vez |
| Custo de bureau | **teto conservador + métrica** | A cota por tenant é o freio de verdade, mas é frente maior; não pode ser pré-requisito para destravar a fila |
| Justiça entre tenants | **FIFO agora** | Noisy neighbor é real, e a solução certa é a cota (P1) — que resolve também ingestão em massa e fatura. Meia-justiça aqui seria segunda cópia da política, e duas cópias divergem |

## Abordagem: claim + fan-out em virtual threads com teto

Para os dois casos: o claim do lote continua idêntico (uma transação curta, lease, `SKIP LOCKED`);
o que muda é que o lote é distribuído em virtual threads, com um semáforo como teto, e o ciclo
espera o lote terminar.

**Virtual threads + `Semaphore`**, e a combinação é o ponto.

O workload é I/O-bound em bureau — caso de uso clássico de Loom — e o Java 25 as tem estáveis (o
_pinning_ em blocos `synchronized`, que era o principal senão, foi resolvido no JDK 24). Mas o
recurso limitante aqui **não é thread**: é o pool de conexões e, sobretudo, o teto de custo de
bureau, que é um controle desejado e não um acidente. `newVirtualThreadPerTaskExecutor()` sozinho
não tem teto — ele aceitaria o lote inteiro e empilharia 50 tarefas sobre 8 conexões.

Por isso as duas coisas juntas:

```java
private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
private final Semaphore permissoes = new Semaphore(workers);
```

O **semáforo** é o teto (o controle de custo); a **virtual thread** é o que faz uma tarefa bloqueada
esperando o bureau não segurar uma thread de plataforma.

**Sendo honesto sobre o ganho hoje:** com teto de 4, isto se comporta praticamente igual a um pool
fixo de 4 — o benefício é marginal. Ele aparece quando o teto sobe: com 50 ou 200 permissões, o
custo por tarefa em espera some, o dimensionamento deixa de ser afinação de pool, e o pool de
conexões vira o backpressure natural. É investimento na direção em que este componente vai crescer,
não otimização do estado atual.

⚠️ **O que virtual thread NÃO resolve:** ela não cria conexão de banco nem cota de bureau. As
amarras de sizing abaixo continuam valendo integralmente.

---

## Componente 1 — `AssessmentProcessor`

```java
@Scheduled(fixedDelayString = "...")
public int process() {
  List<AssessmentId> lote = repository.claimPending(BATCH, lease);
  var tarefas = lote.stream()
      .map(id -> CompletableFuture.supplyAsync(() -> comPermissao(() -> processOne(id)), pool))
      .toList();
  CompletableFuture.allOf(tarefas.toArray(CompletableFuture[]::new)).join();
  return (int) tarefas.stream().filter(CompletableFuture::join).count();
}

/** O semáforo é o teto de concorrência — e, por consequência, o teto de consulta paga. */
private boolean comPermissao(Supplier<Boolean> tarefa) {
  permissoes.acquireUninterruptibly();
  try {
    return tarefa.get();
  } finally {
    permissoes.release();
  }
}
```

### Executor próprio, não o do scheduler

O `spring.task.scheduling.pool.size` é 4, compartilhado com `OutboxRelay`, `WatchlistImporter`,
`AlertEvaluator`, `AssuranceResultPoller` e o purge de idempotência. Fan-out para lá faria a
importação de watchlist das 03:00 competir com o processamento — que é o problema que aquele
`pool.size: 4` já tentou resolver uma vez (era 1). O executor de virtual threads é do processador,
e o `@Scheduled` só dispara o ciclo.

### Esperar o lote é deliberado

Sem o `allOf`, o `fixedDelay` dispararia o próximo claim com o lote anterior em voo, e a
concorrência real seria imprevisível — inaceitável quando a concorrência **é** o controle de custo.

### `MDC`/`Correlation` precisam atravessar a fronteira de thread

`processOne` faz `MDC.put(assessmentId)` e `Correlation.run(assessment.correlationId(), ...)`. Num
pool, cada tarefa nasce com MDC vazio. Sem propagar, o log da decisão volta a nascer órfão — que é
exatamente o que o `Correlation.run` foi escrito para evitar ("ela roda noutra thread, minutos
depois, onde o MDC do servlet já não existe"). **Isto é regressão de observabilidade, não detalhe de
implementação.**

### Sizing tem amarra dura

```
workers ≤ maximum-pool-size − 2      (folga para ingestão e demais jobs)
```

Com `DB_POOL_SIZE=8`, no máximo 6 workers. Subir workers sem subir o pool troca "fila lenta" por
"timeout ao obter conexão" — e a mensagem de erro não distingue os dois casos.

### Configuração e ganho

- `barrier.assessment.workers` (default **4**) — permissões do semáforo, não tamanho de pool
- métrica: o contador `barrier.identity.check` já existe; o alerta é sobre consultas/minuto

Esperado: **6/s → ~25/s** por instância. Não são os 8× teóricos porque o `fixedDelay` de 2s
continua consumindo ~24% do ciclo.

---

## Componente 2 — Entrega de webhook

### 2.1 O listener para de entregar

Hoje `WebhookDeliveryService.record` termina com `attempt(delivery)` — o POST roda **na thread do
listener Kafka**. Um destino que aceita a conexão e demora segura o consumo daquela partição: o
parceiro lento não atrasa só a si mesmo, atrasa todos que compartilham a partição. O timeout mitiga,
não resolve.

Proposto: o listener **persiste e nada mais**; toda entrega passa pelo pool.

**Custo aceito:** a primeira tentativa passa a esperar até um ciclo do scheduler (`retry-delay-ms`,
hoje 5s). Se incomodar, o ciclo cai para 1s — a entrega é assíncrona por contrato de qualquer forma.

### 2.2 Chave de partição, que hoje não existe

`deliveries` conhece `assessment_id`; não conhece subject. E `AssessmentCompletedPayload` **não
carrega `subjectId`** (o `risk_level_changed` carrega).

- **`subjectId` entra no `AssessmentCompletedPayload`** — acréscimo retrocompatível, mesmo padrão do
  `identityReused`/`identityCheckedAt`. A Webhook API repassa o payload como string opaca, então não
  há desserialização estrita a quebrar.
- **coluna `partition_key` em `deliveries`**, preenchida na criação a partir do payload.

### 2.3 Ordem por chave, com o lease fazendo o controle

```sql
UPDATE deliveries SET leased_until = ? WHERE id IN (
  SELECT d.id FROM deliveries d
   WHERE d.status = 'PENDING' AND d.next_attempt_at <= now()
     AND NOT EXISTS (
       SELECT 1 FROM deliveries emVoo
        WHERE emVoo.partition_key = d.partition_key
          AND emVoo.status = 'PENDING'
          AND emVoo.leased_until > now())
   ORDER BY d.created_at
   FOR UPDATE SKIP LOCKED
   LIMIT ?)
RETURNING *
```

**O controle de "em voo" é o próprio lease, no banco.** Nada em memória — funciona igual com 1 pod ou
com 5, por construção.

Isso não é preciosismo: **o padrão "estado do cluster na memória de uma instância" apareceu três
vezes nesta base** (bean de tópicos ignorado, cobertura de watchlist por pod, dedup de alerta por
pod), sempre com um comentário explicando por que estava certo. Um mapa de chaves em voo por
instância seria a quarta.

### O trade-off declarado: bloqueio de cabeça de fila

Ordem por chave **+** retry = entrega que falha **bloqueia as seguintes do mesmo subject**. É o que
"ordem" significa; não é efeito colateral.

A regra que impede bloqueio eterno: **só entrega não-terminal bloqueia a chave.** Esgotadas as
tentativas, a entrega vai para `FAILED`, para de bloquear, e as seguintes fluem. Um parceiro com
endpoint fora do ar acumula backlog daquele subject por, no máximo, o tempo até esgotar o retry —
depois volta a andar, com o buraco registrado e recuperável pelo `DeliveryReconciliationJob`.

### Sizing

Workers ≤ `pool − 2` → com pool 5, **3 workers**. Esperado ~10–20/s → **~50/s** por instância. O teto
de 6 partições não limita aqui, porque a entrega não passa pelo Kafka.

---

## O que escalar para 5 pods faz — e o que não faz

| | ingestão | processamento | razão |
|---|---|---|---|
| hoje, 1 pod | 148/s | 6/s | **25:1** |
| com paralelismo, 1 pod | 148/s | ~25/s | **6:1** |
| 5 pods | ~740/s | ~125/s | **6:1** |

**Réplicas escalam os dois lados; a razão não melhora.** A ingestão é stateless e escala linear; o
processamento esbarra em conexão e bureau.

**E isso está correto.** O `202` existe para desacoplar. O que importa não é pico de ingestão contra
pico de processamento — é **taxa média de chegada contra taxa de drenagem**, mais um teto na *idade*
da fila. Um parceiro com 1 milhão de avaliações/mês é ~0,4/s de média; contra 125/s são ~300× de
folga, e picos entram na fila e drenam no vale.

O teste que mediu 94.222 presas martelou o máximo por cinco minutos. **Não é perfil de produção — é
teste de joelho, e serviu para achar o joelho.**

Portanto o que fecha o problema não é "mais pods", é:

1. **alerta por idade da fila** — já existe (`backlog_analise`, limiar fixo de propósito);
2. **autoscaler por profundidade *e* idade** — já desenhado em `deploy/k8s/autoscaling.yaml`; esta
   medição confirma que **idade** é o gatilho mais importante dos dois;
3. **cota por tenant** (P1) — o que impede o burst de um parceiro de virar a fila de todos.

### Conta de conexões com 5 pods

```
5 × 8 (risk-engine) + 5 × 5 (webhook-api) = 65
```

Cabe em `max_connections = 100`, apertado. Com 6 workers/pod o pool vai a 10 e são 75 — a folga
acaba, e `max_connections` passa a ser parâmetro de capacidade, não de infra.

## Limites conhecidos e não verificados

- **Rate limit do bureau.** 20 consultas simultâneas (5 pods × 4 workers) contra a BigBoost — não se
  sabe se há limite contratual. Se houver e for estourado, o sintoma é `UNAVAILABLE` em massa, que o
  motor converte em revisão manual. **Confirmar antes de subir workers em produção.**
- **`max_connections` do Postgres gerenciado.** Em RDS varia com a classe da instância e pode ser
  menor que 100. A conta acima precisa ser refeita com o número real.
- **Justiça entre tenants** segue ausente nos dois componentes, por decisão registrada acima.

## Testes

| O quê | Como |
|---|---|
| Semáforo é o teto de fato | N tarefas submetidas, no máximo `workers` em execução simultânea — sem isso o teto de custo é decorativo |
| Disjunção sob concorrência | Estender `ConcurrentClaimIntegrationTest`: N workers, nenhuma avaliação processada duas vezes, nenhuma órfã |
| Correlação atravessa o pool | Teste afirmando que o log/MDC da decisão carrega o `correlationId` da requisição original |
| Ordem por subject | Duas entregas do mesmo `partition_key`: a segunda não é reivindicada enquanto a primeira está em voo |
| Chaves distintas em paralelo | Entregas de subjects diferentes são reivindicadas no mesmo lote |
| Terminal não bloqueia | Entrega em `FAILED` deixa de bloquear a chave |
| Ganho real | Repetir a carga do k6 e comparar taxa de drenagem (hoje: 6/s) |
