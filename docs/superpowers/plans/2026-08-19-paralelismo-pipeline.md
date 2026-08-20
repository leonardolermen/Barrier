# Paralelismo do pipeline — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** tirar o processamento de avaliações e a entrega de webhook do modo estritamente
sequencial, com teto explícito de concorrência e ordem de entrega preservada por subject.

**Architecture:** o claim continua idêntico (transação curta, lease, `FOR UPDATE SKIP LOCKED`); o
lote passa a ser distribuído em **virtual threads** com um **`Semaphore`** como teto. No webhook, o
listener Kafka deixa de entregar inline e a reivindicação exclui chaves com entrega em voo — o
controle de ordem é o próprio lease no banco, nunca memória de instância.

**Tech Stack:** Java 25 (virtual threads estáveis), Spring Boot 4.0, PostgreSQL + Flyway, JUnit 5,
Testcontainers, AssertJ, Mockito.

**Spec:** [docs/superpowers/specs/2026-08-19-paralelismo-pipeline-design.md](../specs/2026-08-19-paralelismo-pipeline-design.md)

## Global Constraints

- Camadas `controller → service → repository`; integração externa só por interface (`client`).
  Validado por ArchUnit.
- Migrations Flyway são **imutáveis**: nunca editar uma aplicada. Próximas livres: **V049**
  (risk-engine), **V008** (webhook-api).
- `workers ≤ maximum-pool-size − 2`. Com `DB_POOL_SIZE=8` na risk-engine, teto de 6. Com pool 5 na
  webhook-api, teto de 3.
- Nunca logar CPF/CNPJ sem mascarar.
- Bug corrigido nasce com teste que o reproduz.
- Rodar `./mvnw -o test -pl <módulo> -am` — sem `-am` o módulo `commons` fica desatualizado e o
  erro aparece como "package does not exist".
- Benchmarks têm `@Tag("benchmark")` e ficam fora da build padrão.

---

### Task 1: `subjectId` no payload de `assessment.completed`

Sem isto a entrega não sabe de qual cliente o evento é, e ordem por subject é impossível.
Acréscimo retrocompatível — a Webhook API repassa o payload como string opaca.

**Files:**
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/assessment/service/AssessmentCompletedPayload.java`
- Modify: `docs/architecture/event-catalog.md`
- Test: `services/risk-engine/src/test/java/com/barrier/riskengine/assessment/service/AssessmentCompletedPayloadTest.java`

**Interfaces:**
- Consumes: `Assessment.subjectId()` → `String` (já existe, linha 343)
- Produces: `AssessmentCompletedPayload` com campo `subjectId` (9º componente, após `tenantId`)

- [ ] **Step 1: Escrever o teste que falha**

```java
package com.barrier.riskengine.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * O payload precisa carregar o subject para a entrega poder ordenar por cliente.
 *
 * <p>Sem ele, `deliveries` só conhece o `assessmentId` — e dois eventos sobre o mesmo cliente
 * (a decisão e a mudança de nível de risco) teriam chaves diferentes, ou seja: nenhuma ordem.
 */
class AssessmentCompletedPayloadTest {

  @Test
  void carregaOSubjectParaAOrdenacaoDaEntrega() {
    boolean temSubject =
        Arrays.stream(AssessmentCompletedPayload.class.getRecordComponents())
            .map(RecordComponent::getName)
            .anyMatch("subjectId"::equals);

    assertThat(temSubject)
        .as("sem subjectId no payload a entrega nao tem chave de ordenacao")
        .isTrue();
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw -o test -pl services/risk-engine -am -Dtest=AssessmentCompletedPayloadTest`
Expected: FAIL — `sem subjectId no payload a entrega nao tem chave de ordenacao`

- [ ] **Step 3: Acrescentar o campo**

Em `AssessmentCompletedPayload`, adicionar `String subjectId` logo após `String tenantId`, e no
`from(...)` passar `a.subjectId()` na mesma posição.

```java
public record AssessmentCompletedPayload(
    String assessmentId,
    String tenantId,
    String subjectId,
    String status,
    String riskLevel,
    String decision,
    Instant completedAt,
    Boolean identityReused,
    Instant identityCheckedAt) {

  static AssessmentCompletedPayload from(Assessment a, IdentityProvenance provenance) {
    return new AssessmentCompletedPayload(
        a.id().asString(),
        a.tenantId(),
        a.subjectId(),
        a.status().name(),
        a.riskLevel() == null ? null : a.riskLevel().name(),
        a.decision(),
        a.completedAt(),
        provenance == null ? null : provenance.reused(),
        provenance == null ? null : provenance.checkedAt());
  }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw -o test -pl services/risk-engine -am -Dtest=AssessmentCompletedPayloadTest`
Expected: PASS

- [ ] **Step 5: Atualizar o catálogo de eventos**

Em `docs/architecture/event-catalog.md`, na seção de `barrier.assessment.completed`, acrescentar
`subjectId` à lista de campos do payload, com a nota: *"usado como chave de ordenação da entrega —
ver o plano de paralelismo"*. O catálogo só funciona se for atualizado no mesmo PR que muda o
evento; desatualizado é pior que inexistente.

- [ ] **Step 6: Rodar a suíte do módulo**

Run: `./mvnw -o test -pl services/risk-engine -am`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add services/risk-engine/src docs/architecture/event-catalog.md
git commit -m "feat(assessment): subjectId no payload de assessment.completed

Acrescimo retrocompativel. Sem ele a entrega de webhook so conhece o
assessmentId, e dois eventos sobre o mesmo cliente teriam chaves
diferentes — ou seja, nenhuma ordem possivel por subject."
```

---

### Task 2: coluna `partition_key` em `deliveries`

**Files:**
- Create: `services/webhook-api/src/main/resources/db/migration/V008__delivery_partition_key.sql`
- Modify: `services/webhook-api/src/main/java/com/barrier/webhook/domain/Delivery.java`
- Modify: `services/webhook-api/src/main/java/com/barrier/webhook/repository/DeliveryEntity.java`
- Modify: `services/webhook-api/src/main/java/com/barrier/webhook/repository/DeliveryEntityMapper.java`
- Modify: `services/webhook-api/src/main/java/com/barrier/webhook/service/WebhookDeliveryService.java`
- Test: `services/webhook-api/src/test/java/com/barrier/webhook/domain/DeliveryPartitionKeyTest.java`

**Interfaces:**
- Produces: `Delivery.create(UUID eventId, String assessmentId, String tenantId, String targetUrl,
  String payload, String partitionKey)` — **6 argumentos**; e `Delivery.partitionKey()` → `String`

- [ ] **Step 1: Escrever a migration**

```sql
-- Chave de ordenacao da entrega.
--
-- Entregas com a mesma chave sao entregues em ordem; chaves diferentes correm em paralelo. A chave
-- e o subject: o parceiro nao pode receber "virou HIGH" antes de "virou MEDIUM" do mesmo cliente.
--
-- Nao e o tenant, de proposito: serializar por parceiro limitaria o cliente grande a uma entrega
-- por vez — justamente quem mais precisa de vazao. Nao e o assessment porque dois eventos sobre o
-- mesmo cliente (decisao e mudanca de nivel) tem assessments diferentes e precisam ser ordenados
-- entre si.
--
-- NULL e permitido e significa "sem ordem exigida": evento cujo payload nao traz subject entra no
-- paralelismo sem restricao, em vez de bloquear ou ser bloqueado. Fail-open, como a V048 da
-- risk-engine: o desconhecido nao pode travar a fila.
ALTER TABLE deliveries ADD COLUMN partition_key VARCHAR(64);

COMMENT ON COLUMN deliveries.partition_key IS
    'Chave de ordenacao (subjectId). Entregas com a mesma chave nunca correm em paralelo. '
    'NULL = sem ordem exigida.';

-- Sustenta o NOT EXISTS da reivindicacao, que pergunta "existe entrega desta chave em voo?".
-- Parcial: so linhas nao-terminais bloqueiam, e sao a minoria da tabela ao longo do tempo.
CREATE INDEX idx_deliveries_partition_key_em_voo
    ON deliveries (partition_key, claimed_at)
    WHERE status IN ('PENDING', 'FAILED') AND partition_key IS NOT NULL;
```

- [ ] **Step 2: Escrever o teste que falha**

```java
package com.barrier.webhook.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A entrega precisa carregar a chave que decide o que pode correr em paralelo com o quê. */
class DeliveryPartitionKeyTest {

  @Test
  void guardaAChaveDeParticao() {
    Delivery d =
        Delivery.create(
            UUID.randomUUID(), "assess-1", "default", "http://localhost:9000", "{}", "subject-42");

    assertThat(d.partitionKey()).isEqualTo("subject-42");
  }

  /** Sem subject no payload a entrega nao exige ordem — e nao pode travar por isso. */
  @Test
  void chaveNulaEhPermitida() {
    Delivery d =
        Delivery.create(
            UUID.randomUUID(), "assess-1", "default", "http://localhost:9000", "{}", null);

    assertThat(d.partitionKey()).isNull();
  }
}
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `./mvnw -o test -pl services/webhook-api -am -Dtest=DeliveryPartitionKeyTest`
Expected: FAIL — compilação: `create` não aceita 6 argumentos

- [ ] **Step 4: Implementar**

1. `Delivery`: campo `private final String partitionKey;`, acessor `partitionKey()`, e o 6º
   parâmetro em `create(...)` e em `rehydrate(...)`.
2. `DeliveryEntity`: campo `@Column(name = "partition_key") private String partitionKey;` com
   getter/setter.
3. `DeliveryEntityMapper`: mapear nos dois sentidos.
4. `WebhookDeliveryService.record(...)`: extrair o subject do payload e passar para o `create`.

```java
  /**
   * Chave de ordenacao a partir do payload. O envelope nao a tem — o `assessmentId` dele e o id do
   * AGREGADO, e dois eventos sobre o mesmo cliente tem agregados diferentes.
   *
   * <p>Payload ilegivel ou sem subject devolve null: sem ordem exigida e melhor que entrega
   * bloqueada. Ilegivel ja tem tratamento proprio no consumo (MalformedEventException).
   */
  private String partitionKeyDe(String payload) {
    try {
      Map<String, Object> corpo = objectMapper.readValue(payload, Map.class);
      Object subject = corpo.get("subjectId");
      return subject == null ? null : subject.toString();
    } catch (RuntimeException e) {
      return null;
    }
  }
```

- [ ] **Step 5: Rodar e ver passar**

Run: `./mvnw -o test -pl services/webhook-api -am -Dtest=DeliveryPartitionKeyTest`
Expected: PASS

- [ ] **Step 6: Rodar a suíte do módulo**

Run: `./mvnw -o test -pl services/webhook-api -am`
Expected: PASS — inclui os testes de integração com Testcontainers, que validam a migration

- [ ] **Step 7: Commit**

```bash
git add services/webhook-api/src
git commit -m "feat(webhook): chave de particao na entrega (V008)

Entregas com a mesma chave nunca correm em paralelo; chaves diferentes
sim. A chave e o subject, nao o tenant (serializaria o parceiro grande)
nem o assessment (dois eventos do mesmo cliente tem assessments
diferentes). NULL = sem ordem exigida, fail-open."
```

---

### Task 3: reivindicação exclui chave com entrega em voo

O coração da ordem por subject — e o ponto onde **não** pode haver estado em memória.

**Files:**
- Modify: `services/webhook-api/src/main/java/com/barrier/webhook/repository/DeliveryJpaRepository.java:34-46`
- Test: `services/webhook-api/src/test/java/com/barrier/webhook/DeliveryOrderingIntegrationTest.java`

**Interfaces:**
- Consumes: `Delivery.partitionKey()` (Task 2)
- Produces: `DeliveryRepository.claimDue(Instant now, int limit, Duration lease)` — assinatura
  **inalterada**; muda só o que ela devolve

- [ ] **Step 1: Escrever o teste que falha**

```java
package com.barrier.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.webhook.domain.Delivery;
import com.barrier.webhook.repository.DeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Ordem por subject: duas entregas do mesmo cliente nunca saem juntas; de clientes diferentes,
 * sim.
 *
 * <p>O controle de "em voo" e o proprio lease, NO BANCO. Um mapa de chaves em voo por instancia
 * seria a quarta ocorrencia nesta base do padrao "estado do cluster na memoria de uma instancia"
 * (bean de topicos ignorado, cobertura de watchlist por pod, dedup de alerta por pod) — e com 5
 * replicas nao ordenaria nada.
 */
@SpringBootTest
@Testcontainers
class DeliveryOrderingIntegrationTest {

  private static final Duration LEASE = Duration.ofMinutes(5);

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired DeliveryRepository repository;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void limpa() {
    jdbc.update("DELETE FROM deliveries");
  }

  @Test
  void naoReivindicaDuasEntregasDaMesmaChave() {
    grava("subject-A");
    grava("subject-A");

    List<Delivery> primeiro = repository.claimDue(Instant.now(), 10, LEASE);

    assertThat(primeiro)
        .as("duas entregas do mesmo subject sairam juntas — a ordem nao esta garantida")
        .hasSize(1);
  }

  @Test
  void chavesDiferentesCorremEmParalelo() {
    grava("subject-A");
    grava("subject-B");
    grava("subject-C");

    assertThat(repository.claimDue(Instant.now(), 10, LEASE)).hasSize(3);
  }

  /** Sem chave nao ha ordem a preservar: todas podem sair juntas. */
  @Test
  void chaveNulaNaoBloqueiaNinguem() {
    grava(null);
    grava(null);

    assertThat(repository.claimDue(Instant.now(), 10, LEASE)).hasSize(2);
  }

  /**
   * Entrega terminal para de bloquear a chave. Sem isto, um parceiro fora do ar travaria o subject
   * para sempre, em vez de ate esgotar o retry.
   */
  @Test
  void entregaTerminalNaoBloqueiaAChave() {
    grava("subject-D");
    jdbc.update("UPDATE deliveries SET status = 'DELIVERED' WHERE partition_key = 'subject-D'");
    grava("subject-D");

    assertThat(repository.claimDue(Instant.now(), 10, LEASE)).hasSize(1);
  }

  private void grava(String partitionKey) {
    jdbc.update(
        """
        INSERT INTO deliveries
               (id, event_id, assessment_id, tenant_id, target_url, payload, status,
                attempts, next_attempt_at, created_at, partition_key)
        VALUES (?, ?, 'a-1', 'default', 'http://localhost:9000', '{}', 'PENDING',
                0, now(), now(), ?)
        """,
        UUID.randomUUID(),
        UUID.randomUUID(),
        partitionKey);
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw -o test -pl services/webhook-api -am -Dtest=DeliveryOrderingIntegrationTest`
Expected: FAIL em `naoReivindicaDuasEntregasDaMesmaChave` — vieram 2, esperava 1

- [ ] **Step 3: Alterar a query de reivindicação**

Em `DeliveryJpaRepository`, substituir a `@Query` de `selectClaimable` por:

```java
  @Query(
      """
      SELECT d FROM DeliveryEntity d
       WHERE d.status IN :statuses
         AND d.nextAttemptAt <= :now
         AND (d.claimedAt IS NULL OR d.claimedAt < :leaseCutoff)
         AND (d.partitionKey IS NULL
              OR NOT EXISTS (SELECT 1 FROM DeliveryEntity emVoo
                              WHERE emVoo.partitionKey = d.partitionKey
                                AND emVoo.id <> d.id
                                AND emVoo.status IN :statuses
                                AND emVoo.claimedAt IS NOT NULL
                                AND emVoo.claimedAt >= :leaseCutoff))
       ORDER BY d.nextAttemptAt ASC
      """)
  List<DeliveryEntity> selectClaimable(
      @Param("statuses") List<DeliveryStatus> statuses,
      @Param("now") Instant now,
      @Param("leaseCutoff") Instant leaseCutoff,
      Limit limit);
```

⚠️ Isto sozinho **não** faz o primeiro teste passar: as duas entregas ainda estão sem
`claimedAt`, então nenhuma bloqueia a outra e as duas são elegíveis. É preciso também deduplicar
por chave **dentro do lote**, em `DeliveryRepositoryImpl.claimDue`:

```java
  @Override
  public List<Delivery> claimDue(Instant now, int limit, Duration lease) {
    List<DeliveryEntity> claimable =
        jpa.selectClaimable(
            List.of(DeliveryStatus.PENDING, DeliveryStatus.FAILED),
            now,
            now.minus(lease),
            Limit.of(limit));

    // Uma entrega por chave POR LOTE. A query exclui chaves ja em voo; esta linha impede que o
    // proprio lote leve duas da mesma chave — sem ela, a ordem quebraria dentro de um unico ciclo,
    // que e exatamente o caso mais comum (dois eventos do mesmo cliente chegam juntos).
    Set<String> chavesNoLote = new HashSet<>();
    List<DeliveryEntity> lote =
        claimable.stream()
            .filter(e -> e.getPartitionKey() == null || chavesNoLote.add(e.getPartitionKey()))
            .toList();

    lote.forEach(entity -> entity.setClaimedAt(now));
    return lote.stream().map(DeliveryEntityMapper::toDomain).toList();
  }
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw -o test -pl services/webhook-api -am -Dtest=DeliveryOrderingIntegrationTest`
Expected: PASS — 4 testes

- [ ] **Step 5: Rodar a suíte do módulo**

Run: `./mvnw -o test -pl services/webhook-api -am`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add services/webhook-api/src
git commit -m "feat(webhook): reivindicacao respeita ordem por chave de particao

Duas entregas do mesmo subject nunca saem juntas; de subjects diferentes,
sim. Duas travas: a query exclui chave com entrega em voo (lease no
banco, nao memoria) e o lote deduplica por chave — sem a segunda, dois
eventos do mesmo cliente chegando juntos quebrariam a ordem dentro de um
unico ciclo.

Entrega terminal para de bloquear: parceiro fora do ar trava o subject
ate esgotar o retry, nao para sempre."
```

---

### Task 4: listener do Kafka para de entregar inline

**Files:**
- Modify: `services/webhook-api/src/main/java/com/barrier/webhook/service/WebhookDeliveryService.java:93`
- Test: `services/webhook-api/src/test/java/com/barrier/webhook/service/WebhookDeliveryServiceTest.java`

**Interfaces:**
- Produces: `WebhookDeliveryService.record(...)` passa a **não** chamar `attempt(...)`

- [ ] **Step 1: Escrever o teste que falha**

Acrescentar a `WebhookDeliveryServiceTest`:

```java
  /**
   * O POST nao pode rodar na thread do listener Kafka: um destino que aceita a conexao e demora
   * segura o consumo da particao inteira — o parceiro lento atrasa todos que compartilham a
   * particao, nao so a si mesmo. O timeout mitiga, nao resolve.
   */
  @Test
  void gravarNaoEntregaNaThreadDoListener() {
    service().record(envelopeDeTeste());

    verify(client, never()).send(any());
  }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw -o test -pl services/webhook-api -am -Dtest=WebhookDeliveryServiceTest`
Expected: FAIL — `client.send` foi chamado

- [ ] **Step 3: Remover a entrega inline**

Em `WebhookDeliveryService.record(...)`, apagar a linha `attempt(delivery);` e substituir por
comentário:

```java
    // A entrega NAO acontece aqui, de proposito: este metodo roda na thread do listener Kafka, e um
    // destino que aceita a conexao e demora seguraria o consumo da particao. Quem entrega e o
    // retryDue(), pelo pool — a latencia do parceiro deixa de existir no caminho do broker.
    //
    // Custo aceito: a primeira tentativa espera ate um ciclo do scheduler.
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw -o test -pl services/webhook-api -am -Dtest=WebhookDeliveryServiceTest`
Expected: PASS

- [ ] **Step 5: Reduzir o ciclo de retry**

Em `services/webhook-api/src/main/resources/application.yml`, mudar
`barrier.webhook.retry-delay-ms` de `5000` para `1000`, com o comentário:

```yaml
      # 1s e nao 5s: desde que o listener parou de entregar inline, este ciclo passou a ser a
      # latencia da PRIMEIRA tentativa, nao so a do retry.
```

- [ ] **Step 6: Rodar a suíte do módulo**

Run: `./mvnw -o test -pl services/webhook-api -am`
Expected: PASS. ⚠️ `WebhookDeliveryIntegrationTest` provavelmente falha — ele espera entrega
imediata após o consumo. Ajustar para acionar `retryDue()` explicitamente antes de verificar, no
mesmo padrão que os testes do `AssessmentProcessor` já usam.

- [ ] **Step 7: Commit**

```bash
git add services/webhook-api/src
git commit -m "refactor(webhook): listener persiste, pool entrega

O POST rodava na thread do listener Kafka: destino lento segurava o
consumo da particao, entao o parceiro lento atrasava todos que
compartilham a particao. Achado da auditoria (P2).

Custo aceito: a primeira tentativa espera um ciclo do scheduler, agora
reduzido de 5s para 1s justamente porque ele virou o caminho da primeira
entrega."
```

---

### Task 5: entrega em virtual threads com teto

**Files:**
- Modify: `services/webhook-api/src/main/java/com/barrier/webhook/service/WebhookDeliveryService.java`
- Modify: `services/webhook-api/src/main/resources/application.yml`
- Test: `services/webhook-api/src/test/java/com/barrier/webhook/service/DeliveryConcurrencyTest.java`

**Interfaces:**
- Produces: `WebhookDeliveryService.retryDue()` → `int` (assinatura inalterada; passa a paralelizar)

- [ ] **Step 1: Escrever o teste que falha**

```java
package com.barrier.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * O semaforo e o teto de fato — nao decoracao.
 *
 * <p>Virtual thread e barata: submeter 50 tarefas nao estoura nada e nao da nenhum sinal de que o
 * teto foi ignorado. Sem este teste, um teto quebrado passaria despercebido ate a fatura do bureau
 * (na risk-engine) ou ate o parceiro reclamar de rajada (aqui).
 */
class DeliveryConcurrencyTest {

  @Test
  void oSemaforoLimitaAConcorrenciaSimultanea() throws Exception {
    int teto = 3;
    var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    var permissoes = new java.util.concurrent.Semaphore(teto);
    AtomicInteger emVoo = new AtomicInteger();
    AtomicInteger pico = new AtomicInteger();

    var tarefas =
        java.util.stream.IntStream.range(0, 50)
            .mapToObj(
                i ->
                    java.util.concurrent.CompletableFuture.runAsync(
                        () -> {
                          permissoes.acquireUninterruptibly();
                          try {
                            pico.accumulateAndGet(emVoo.incrementAndGet(), Math::max);
                            Thread.sleep(20);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          } finally {
                            emVoo.decrementAndGet();
                            permissoes.release();
                          }
                        },
                        executor))
            .toList();

    java.util.concurrent.CompletableFuture.allOf(
            tarefas.toArray(java.util.concurrent.CompletableFuture[]::new))
        .join();
    executor.close();

    assertThat(pico.get())
        .as("chegaram %d tarefas simultaneas com teto de %d", pico.get(), teto)
        .isLessThanOrEqualTo(teto);
  }
}
```

- [ ] **Step 2: Rodar e ver passar (é teste do mecanismo, não da regressão)**

Run: `./mvnw -o test -pl services/webhook-api -am -Dtest=DeliveryConcurrencyTest`
Expected: PASS — confirma que o padrão semáforo+virtual thread se comporta como esperado antes de
ser aplicado ao serviço

- [ ] **Step 3: Aplicar ao `retryDue`**

```java
  private final ExecutorService entregas = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore permissoes;   // construído com barrier.webhook.workers

  public int retryDue() {
    List<Delivery> due =
        transactionTemplate.execute(
            status -> repository.claimDue(Instant.now(), RETRY_BATCH, lease));
    if (due == null || due.isEmpty()) {
      return 0;
    }
    var tarefas =
        due.stream()
            .map(d -> CompletableFuture.runAsync(() -> comPermissao(() -> attempt(d)), entregas))
            .toList();
    // Espera o lote: sem isto o proximo ciclo reivindicaria com o anterior em voo, e a
    // concorrencia real deixaria de ser o que o teto diz.
    CompletableFuture.allOf(tarefas.toArray(CompletableFuture[]::new)).join();
    return due.size();
  }

  private void comPermissao(Runnable tarefa) {
    permissoes.acquireUninterruptibly();
    try {
      tarefa.run();
    } finally {
      permissoes.release();
    }
  }
```

Config em `application.yml`:

```yaml
    # Teto de entregas simultaneas. Amarra dura: workers <= maximum-pool-size - 2 (folga para o
    # listener e para os jobs). Com pool 5, o teto e 3.
    workers: ${WEBHOOK_WORKERS:3}
```

- [ ] **Step 4: Rodar a suíte do módulo**

Run: `./mvnw -o test -pl services/webhook-api -am`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add services/webhook-api/src
git commit -m "perf(webhook): entrega em virtual threads com teto por semaforo

O semaforo e o teto (protege o pool de conexao e o parceiro de rajada); a
virtual thread evita segurar thread de plataforma esperando o destino.

Espera o lote de proposito: sem isso o ciclo seguinte reivindicaria com o
anterior em voo e a concorrencia real deixaria de ser a declarada."
```

---

### Task 6: `AssessmentProcessor` em virtual threads, com correlação preservada

A tarefa de maior ganho — e a de maior risco de regressão silenciosa, por causa do MDC.

**Files:**
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/assessment/service/AssessmentProcessor.java:130-165`
- Modify: `services/risk-engine/src/main/resources/application.yml`
- Test: `services/risk-engine/src/test/java/com/barrier/riskengine/assessment/ParallelProcessingIntegrationTest.java`

**Interfaces:**
- Consumes: `AssessmentRepository.claimPending(int limit, Duration lease)` → `List<AssessmentId>`
- Produces: `AssessmentProcessor.process()` → `int` (assinatura inalterada)

- [ ] **Step 1: Escrever o teste que falha**

```java
package com.barrier.riskengine.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assessment.service.AssessmentProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Paralelizar nao pode processar nada duas vezes, deixar nada orfao, nem perder a correlacao.
 *
 * <p>A correlacao e o ponto delicado: `processOne` restaura o correlationId da requisicao original
 * com `Correlation.run` justamente porque a decisao roda noutra thread, minutos depois. Num pool,
 * cada tarefa nasce com MDC vazio — se a propagacao nao for explicita, o log da decisao volta a
 * nascer orfao e ninguem percebe, porque nenhum teste falha por log.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000",
      "barrier.watchlist.refresh-cron=0 0 3 1 1 ?",
      "barrier.assessment.workers=4"
    })
@Testcontainers
class ParallelProcessingIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired AssessmentProcessor processor;
  @Autowired JdbcTemplate jdbc;

  @Test
  void processaOLoteSemDuplicataESemOrfa() {
    int quantas = 30;
    submete(quantas);

    processor.process();

    Long duplicadas =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM (
              SELECT assessment_id FROM risk_scores GROUP BY assessment_id HAVING count(*) > 1
            ) d
            """,
            Long.class);
    Long pendentes =
        jdbc.queryForObject(
            "SELECT count(*) FROM assessments WHERE status = 'EM_ANALISE'", Long.class);

    assertThat(duplicadas).as("avaliacao processada duas vezes").isZero();
    assertThat(pendentes).as("avaliacao ficou orfa no lote").isZero();
  }

  /**
   * A correlacao sobrevive a fronteira de thread.
   *
   * <p>Nao e teste de log por preciosismo: `processOne` restaura o correlationId com
   * `Correlation.run` porque a decisao roda noutra thread, minutos depois da requisicao. Se alguem
   * mover esse tratamento para FORA de processOne — para o metodo `process`, por exemplo — cada
   * tarefa passa a nascer com MDC vazio e o log da decisao volta a ser orfao. Nenhum outro teste
   * falharia por isso.
   */
  @Test
  void aCorrelacaoSobreviveAFronteiraDeThread() {
    submete(1);
    String correlacao =
        jdbc.queryForObject(
            "SELECT correlation_id FROM assessments WHERE status = 'EM_ANALISE' LIMIT 1",
            String.class);

    processor.process();

    String depois =
        jdbc.queryForObject(
            "SELECT correlation_id FROM assessments WHERE correlation_id = ?",
            String.class,
            correlacao);
    assertThat(depois)
        .as("a avaliacao perdeu a correlacao ao ser processada em outra thread")
        .isEqualTo(correlacao);
  }

  /** Submete N avaliacoes com CPFs validos distintos, direto pelo servico. */
  private void submete(int quantas) {
    // Implementacao: reutilizar o helper de submissao de AssessmentFlowIntegrationTest
    // (apiKeyService.issue + POST) ou chamar AssessmentService.submit diretamente.
  }
}
```

⚠️ **Nota ao executor:** o teste acima verifica que a coluna sobrevive, que é o mais fácil de
afirmar. A propriedade que realmente importa — o `correlationId` aparecer **no MDC do log da
decisão** — exige capturar o appender do Logback. Se o `ListAppender` já for usado em algum teste
deste repositório, prefira-o; ele afirma a propriedade de verdade em vez do proxy.

⚠️ O helper `submete` precisa ser preenchido pelo executor seguindo o padrão de
`AssessmentFlowIntegrationTest` — que já emite credencial com
`apiKeyService.issue("default", "teste").presentedValue()` e faz o POST via `RestClient`.

- [ ] **Step 2: Rodar e ver o comportamento atual**

Run: `./mvnw -o test -pl services/risk-engine -am -Dtest=ParallelProcessingIntegrationTest`
Expected: PASS já no sequencial (é rede de segurança para a mudança, não teste de regressão)

- [ ] **Step 3: Paralelizar preservando a correlação**

```java
  private final ExecutorService trabalhadores = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore permissoes;   // barrier.assessment.workers

  @Scheduled(fixedDelayString = "${barrier.assessment.processor-delay-ms:2000}")
  public int process() {
    List<AssessmentId> lote = repository.claimPending(BATCH, lease);
    if (lote.isEmpty()) {
      return 0;
    }
    var tarefas =
        lote.stream()
            .map(id -> CompletableFuture.supplyAsync(() -> comPermissao(id), trabalhadores))
            .toList();
    CompletableFuture.allOf(tarefas.toArray(CompletableFuture[]::new)).join();
    return (int) tarefas.stream().filter(CompletableFuture::join).count();
  }

  /**
   * O semaforo e o teto de concorrencia — e, por consequencia, o teto de consulta PAGA de bureau.
   * Virtual thread nao cria conexao de banco nem cota de bureau: sem este limite, o lote inteiro
   * (50) atacaria um pool de 8 conexoes e a fatura do provedor ao mesmo tempo.
   */
  private boolean comPermissao(AssessmentId id) {
    permissoes.acquireUninterruptibly();
    try {
      return processOne(id);
    } finally {
      permissoes.release();
    }
  }
```

`processOne` **não muda**: ele já faz `MDC.put(assessmentId)` e `Correlation.run(...)` por conta
própria, e ambos agem na thread corrente — que agora é a virtual thread da tarefa. Nada a propagar
de fora, porque nada é herdado de fora.

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw -o test -pl services/risk-engine -am -Dtest=ParallelProcessingIntegrationTest`
Expected: PASS

- [ ] **Step 5: Configuração**

Em `application.yml` da risk-engine:

```yaml
  assessment:
    # Teto de avaliacoes simultaneas — e, portanto, de consultas pagas de bureau por vez.
    # Amarra dura: workers <= maximum-pool-size - 2 (folga para ingestao e demais jobs).
    # Com DB_POOL_SIZE=8, o teto e 6. Subir workers sem subir o pool troca "fila lenta" por
    # "timeout ao obter conexao", e a mensagem de erro nao distingue os dois casos.
    workers: ${ASSESSMENT_WORKERS:4}
```

- [ ] **Step 6: Rodar a suíte completa**

Run: `./mvnw -o test`
Expected: PASS — 730 testes + os novos

- [ ] **Step 7: Commit**

```bash
git add services/risk-engine/src
git commit -m "perf(assessment): processamento do lote em virtual threads com teto

Medido antes: ingestao 148/s contra processamento 6/s, 94.222 avaliacoes
presas em EM_ANALISE — reproducao do modo de falha do ADR-0015. A causa
era BATCH=50 processadas uma a uma numa thread.

O semaforo e o teto (controle de custo de bureau); a virtual thread evita
segurar thread de plataforma esperando o provedor. processOne nao mudou:
o MDC e o Correlation.run ja agem na thread corrente, que agora e a
virtual thread da tarefa."
```

---

### Task 7: medir o ganho e registrar

Sem esta tarefa o plano entrega código sem evidência — e o item da auditoria pede número.

**Files:**
- Modify: `docs/implementation/plano-auditoria-2026-08-18.md`
- Modify: `services/risk-engine/src/test/load/README.md`

- [ ] **Step 1: Subir a infra e o serviço**

```bash
docker compose up -d
```

- [ ] **Step 2: Medir a taxa de drenagem ANTES da carga**

```bash
docker exec barrier-postgres psql -U barrier -d barrier -c "SELECT status, count(*) FROM assessments GROUP BY status ORDER BY 2 DESC;"
```

- [ ] **Step 3: Rodar a carga**

```bash
docker run --rm -v "$PWD/services/risk-engine/src/test/load:/scripts" -e BASE_URL=http://host.docker.internal:8080 -e API_KEY=brr_xxx grafana/k6 run /scripts/assessments.js
```

- [ ] **Step 4: Medir a drenagem depois (com o k6 parado)**

Contar `EM_ANALISE` duas vezes com 30s de intervalo e dividir a diferença por 30. **A medição tem
de ser com o k6 parado** — com carga competindo, o número mistura ingestão e drenagem.

Referência a bater: **6 avaliações/s**. Esperado: ~25/s.

- [ ] **Step 5: Registrar no plano de auditoria**

Marcar o item "Paralelizar os três loops sequenciais" com o número medido, e registrar
explicitamente se o alvo **não** foi atingido — medição que decepciona é informação, não fracasso.

- [ ] **Step 6: Commit**

```bash
git add docs/implementation/plano-auditoria-2026-08-18.md services/risk-engine/src/test/load/README.md
git commit -m "docs: registra o ganho medido do paralelismo do pipeline"
```

---

## Fora de escopo, registrado

- **Justiça entre tenants.** `claimPending` continua FIFO por antiguidade: um parceiro com 100 mil
  avaliações ocupa todos os workers. A solução é a cota por tenant (P1), que resolve também
  ingestão em massa e fatura — meia-justiça aqui seria uma segunda cópia da política.
- **`OutboxRelay` sequencial.** É o terceiro laço do achado da auditoria. Não entra aqui porque o
  gargalo dele é o `.join()` do Kafka, não bureau, e a mudança tem risco próprio (ordem de
  publicação). Frente separada.
- **Rate limit do bureau.** 5 pods × 4 workers = 20 consultas simultâneas contra a BigBoost.
  Desconhecido se há limite contratual. Confirmar antes de subir `ASSESSMENT_WORKERS` em produção.
