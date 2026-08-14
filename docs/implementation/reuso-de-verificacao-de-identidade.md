# Reuso de verificação de identidade — plano de implementação

> **Para executores agênticos:** SUB-SKILL OBRIGATÓRIA — use `superpowers:subagent-driven-development`
> (recomendado) ou `superpowers:executing-plans` para implementar tarefa a tarefa. Os passos usam
> checkbox (`- [ ]`) para acompanhamento.

**Objetivo:** parar de pagar a mesma consulta de bureau duas vezes, reaproveitando uma
verificação de identidade recente do mesmo documento no mesmo tenant — com a trilha dizendo,
sem ambiguidade, que houve reuso e de qual consulta.

**Arquitetura:** `IdentityService.verify` passa a consultar `identity_checks` antes de sair
para a rede. Havendo um check **elegível** (desfecho definitivo, mesmo tenant, mesmo documento,
mesmo nome normalizado, dentro do TTL), grava-se um check **novo** para esta avaliação copiando
o desfecho e apontando para o original em `reused_from_id`. Cada avaliação continua tendo seu
próprio `identity_check` — `RiskScore.identityCheckId` continua identificando exatamente a
verificação que sustentou aquela decisão, que é a garantia da V028.

**Stack:** Java 25 · Spring Boot 4 · Postgres + Flyway · JUnit 5 · Mockito · AssertJ · Testcontainers

**Spec:** [licoes-do-origem.md](licoes-do-origem.md) § Prioridade 1. Origem do mecanismo:
`bureaus-manager` (cache 24h) e `adr-derivacao-quadro-custos.md` D3 (TTL de reuso) do
ecossistema BMP.

## Restrições globais

- **Só CPF nesta entrega.** CNPJ fica fora: `IdentityResult.company` (o `CompanyProfile` com
  QSA/CNAE/abertura) é **transiente**, não persistido no check — reusar um check de PJ
  devolveria `company == null` e a `CorporateStructureRiskRule` pararia de disparar em silêncio.
  Isso é fail-open, exatamente o padrão que a auditoria mandou eliminar. Reuso de PJ exige
  reidratar o perfil do `raw_response` e é entrega própria.
- **Só desfecho definitivo.** `UNAVAILABLE` nunca é reusado — congelaria uma indisponibilidade
  passada como se fosse resposta.
- **Escopo do reuso é o tenant.** Reuso entre tenants exige ADR próprio (CLAUDE.md:
  "cache compartilhado de dados objetivos entre tenants = futuro opt-in"; o motivo está no
  [ADR-0012](../adr/0012-subject-registration-profile.md)).
- **Nome entra na chave.** O check compara nome contra o bureau; um `MATCH` para "MARIA SILVA"
  não vale para "MARIA SILVA SANTOS" no mesmo CPF.
- Desligado por padrão: `barrier.identity.reuse.enabled=false`.
- Toda mudança de regra/peso sobe `ENGINE_VERSION` — **este plano não muda nenhuma regra**, só a
  procedência do insumo. `ENGINE_VERSION` **não** sobe.
- `./mvnw spotless:apply` não roda no JDK 25 — formatar à mão (CLAUDE.md).
- Rodar testes com `JAVA_HOME` apontando para o JDK 25.

---

### Task 1: Coluna de busca e procedência em `identity_checks`

Hoje `identity_checks` só é pesquisável por `assessment_id`. Sem documento e tenant na tabela,
não existe consulta de reuso.

**Files:**
- Create: `services/risk-engine/src/main/resources/db/migration/V036__identity_check_reuse.sql`
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/identity/repository/IdentityCheckEntity.java`
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/identity/domain/IdentityCheck.java`
- Test: `services/risk-engine/src/test/java/com/barrier/riskengine/identity/IdentityCheckTest.java`

**Interfaces:**
- Produz: `IdentityCheck` com os campos novos `tenantId`, `documentType`, `documentDigits`,
  `name`, `reusedFromId` (todos após `assessmentId`, antes de `status`), e a fábrica
  `IdentityCheck.reusing(String assessmentId, IdentityCheck original)`.

- [ ] **Passo 1: escrever a migration**

```sql
-- V036: identity_checks passa a ser pesquisável por (tenant, documento, nome) para permitir
-- reuso de uma verificação recente em vez de pagar a mesma consulta de bureau outra vez.
--
-- reused_from_id é o que impede a trilha de mentir: um check reaproveitado tem checked_at de
-- agora (é quando esta avaliação decidiu) e aponta para a consulta que de fato foi à rede.
-- Sem essa coluna, evidência reaproveitada e evidência fresca ficam indistinguíveis.

ALTER TABLE identity_checks ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE identity_checks ADD COLUMN document_type VARCHAR(10);
ALTER TABLE identity_checks ADD COLUMN document_digits VARCHAR(14);
ALTER TABLE identity_checks ADD COLUMN name VARCHAR(200);
ALTER TABLE identity_checks ADD COLUMN reused_from_id UUID REFERENCES identity_checks (id);

-- Parcial: só linhas com documento preenchido servem de origem de reuso, e as linhas
-- históricas (anteriores a esta migration) nunca terão. O índice não paga por elas.
CREATE INDEX idx_identity_checks_reuse
  ON identity_checks (tenant_id, document_type, document_digits, checked_at DESC)
  WHERE document_digits IS NOT NULL AND reused_from_id IS NULL;

COMMENT ON COLUMN identity_checks.reused_from_id IS
  'Quando preenchido, este check copiou o desfecho da consulta apontada em vez de ir ao bureau.';
```

Colunas nullable de propósito: as linhas que já existem não têm esses dados e a migration não
inventa valor para elas.

- [ ] **Passo 2: adicionar os campos na entidade JPA**

Em `IdentityCheckEntity`, depois do campo `assessmentId`:

```java
  @Column(name = "tenant_id", length = 64)
  private String tenantId;

  @Column(name = "document_type", length = 10)
  private String documentType;

  @Column(name = "document_digits", length = 14)
  private String documentDigits;

  @Column(name = "name", length = 200)
  private String name;

  @Column(name = "reused_from_id")
  private UUID reusedFromId;
```

Adicionar getters/setters no mesmo estilo dos existentes, e mapear os cinco campos nos dois
sentidos onde a entidade converte de/para `IdentityCheck`.

- [ ] **Passo 3: escrever o teste do domínio (vai falhar)**

```java
  @Test
  void checkReaproveitadoCopiaDesfechoEApontaParaOriginal() {
    IdentityCheck original =
        IdentityCheck.create(
            "aval-1", "tenant-a", "CPF", "11144477735", "MARIA SILVA",
            IdentityStatus.VERIFIED, "bigboost", "titular regular", "query-99", "{}");

    IdentityCheck reuso = IdentityCheck.reusing("aval-2", original);

    assertThat(reuso.assessmentId()).isEqualTo("aval-2");
    assertThat(reuso.status()).isEqualTo(IdentityStatus.VERIFIED);
    assertThat(reuso.provider()).isEqualTo("bigboost");
    assertThat(reuso.providerReference()).isEqualTo("query-99");
    assertThat(reuso.reusedFromId()).isEqualTo(original.id());
    assertThat(reuso.id()).isNotEqualTo(original.id());
  }

  @Test
  void checkReaproveitadoNaoCopiaARespostaBruta() {
    IdentityCheck original =
        IdentityCheck.create(
            "aval-1", "tenant-a", "CPF", "11144477735", "MARIA SILVA",
            IdentityStatus.VERIFIED, "bigboost", "titular regular", "query-99", "{\"a\":1}");

    assertThat(IdentityCheck.reusing("aval-2", original).rawResponse()).isNull();
  }
```

O segundo teste é a decisão que evita duplicar dado pessoal: a resposta bruta do bureau fica em
**uma** linha, apontada por `reused_from_id`. Copiá-la multiplicaria PII sem acrescentar
evidência.

- [ ] **Passo 4: rodar e ver falhar**

```bash
./mvnw -pl services/risk-engine test -Dtest=IdentityCheckTest
```

Esperado: falha de compilação — `reusing` e a fábrica de 10 argumentos não existem.

- [ ] **Passo 5: implementar no record**

Adicionar os cinco componentes ao record `IdentityCheck`, manter as fábricas atuais delegando
com `null` nos campos novos (para não quebrar os chamadores existentes de uma vez) e acrescentar:

```java
  /**
   * Verificação que reaproveitou uma consulta anterior em vez de ir ao bureau.
   *
   * <p>{@code checkedAt} é <b>agora</b>, não o instante da consulta original: este é o momento em
   * que esta avaliação decidiu. Quando a consulta de fato aconteceu se lê seguindo
   * {@code reusedFromId} — que é por isso que a coluna existe.
   *
   * <p>A resposta bruta não é copiada: PII duplicada não é evidência a mais, e o original
   * continua acessível pelo ponteiro.
   */
  public static IdentityCheck reusing(String assessmentId, IdentityCheck original) {
    return new IdentityCheck(
        UUID.randomUUID(),
        assessmentId,
        original.tenantId(),
        original.documentType(),
        original.documentDigits(),
        original.name(),
        original.status(),
        original.provider(),
        original.detail(),
        Instant.now(),
        original.providerReference(),
        null,
        original.id());
  }

  /** Este check foi à rede, ou copiou outro? */
  public boolean isReused() {
    return reusedFromId != null;
  }
```

- [ ] **Passo 6: rodar e ver passar**

```bash
./mvnw -pl services/risk-engine test -Dtest=IdentityCheckTest
```

Esperado: PASS.

- [ ] **Passo 7: rodar a suíte inteira**

```bash
./mvnw -pl services/risk-engine test
```

Esperado: 275+ testes verdes. Se algo quebrou, é chamador da fábrica antiga — ajustar.

- [ ] **Passo 8: commit**

```bash
git add services/risk-engine/src/main/resources/db/migration/V036__identity_check_reuse.sql \
        services/risk-engine/src/main/java/com/barrier/riskengine/identity/ \
        services/risk-engine/src/test/java/com/barrier/riskengine/identity/IdentityCheckTest.java
git commit -m "feat(identity): campos de busca e procedencia em identity_checks"
```

---

### Task 2: Consulta de check reaproveitável

**Files:**
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/identity/repository/interfaces/IdentityCheckRepository.java`
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/identity/repository/interfaces/IdentityCheckJpaRepository.java`
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/identity/repository/IdentityCheckRepositoryImpl.java`
- Test: `services/risk-engine/src/test/java/com/barrier/riskengine/identity/IdentityCheckReuseQueryIT.java`

**Interfaces:**
- Consome: campos da Task 1.
- Produz: `Optional<IdentityCheck> findReusable(String tenantId, String documentType, String documentDigits, String name, Instant notBefore)`.

- [ ] **Passo 1: escrever o teste de integração (vai falhar)**

```java
  @Test
  void naoReaproveitaCheckDeOutroTenant() {
    salvar("tenant-a", "CPF", "11144477735", "MARIA SILVA", IdentityStatus.VERIFIED, Instant.now());

    assertThat(
            repository.findReusable(
                "tenant-b", "CPF", "11144477735", "MARIA SILVA", Instant.now().minus(Duration.ofHours(24))))
        .isEmpty();
  }

  @Test
  void naoReaproveitaCheckForaDaJanela() {
    salvar("tenant-a", "CPF", "11144477735", "MARIA SILVA", IdentityStatus.VERIFIED,
        Instant.now().minus(Duration.ofHours(30)));

    assertThat(
            repository.findReusable(
                "tenant-a", "CPF", "11144477735", "MARIA SILVA", Instant.now().minus(Duration.ofHours(24))))
        .isEmpty();
  }

  @Test
  void naoReaproveitaCheckDeNomeDiferenteNoMesmoDocumento() {
    salvar("tenant-a", "CPF", "11144477735", "MARIA SILVA", IdentityStatus.VERIFIED, Instant.now());

    assertThat(
            repository.findReusable(
                "tenant-a", "CPF", "11144477735", "MARIA SILVA SANTOS",
                Instant.now().minus(Duration.ofHours(24))))
        .isEmpty();
  }

  @Test
  void reaproveitaOCheckMaisRecenteDentroDaJanela() {
    salvar("tenant-a", "CPF", "11144477735", "MARIA SILVA", IdentityStatus.VERIFIED,
        Instant.now().minus(Duration.ofHours(10)));
    IdentityCheck recente =
        salvar("tenant-a", "CPF", "11144477735", "MARIA SILVA", IdentityStatus.VERIFIED,
            Instant.now().minus(Duration.ofHours(1)));

    assertThat(
            repository.findReusable(
                "tenant-a", "CPF", "11144477735", "MARIA SILVA",
                Instant.now().minus(Duration.ofHours(24))))
        .map(IdentityCheck::id)
        .contains(recente.id());
  }
```

- [ ] **Passo 2: rodar e ver falhar**

```bash
./mvnw -pl services/risk-engine test -Dtest=IdentityCheckReuseQueryIT
```

Esperado: falha de compilação — `findReusable` não existe.

- [ ] **Passo 3: declarar na interface de domínio**

```java
  /**
   * Verificação anterior que pode ser reaproveitada por uma avaliação nova, se existir.
   *
   * <p>Escopada ao tenant de propósito: dado objetivo de bureau é compartilhável em tese, mas
   * cruzar tenants aqui repetiria o erro que o ADR-0012 corrigiu no cadastro. Fica como opt-in
   * futuro, com ADR próprio.
   *
   * <p>Não devolve check que já é reuso ({@code reused_from_id IS NULL}): reuso de reuso
   * encadearia a procedência e afastaria a decisão da consulta real sem que a distância
   * aparecesse em lugar nenhum.
   */
  Optional<IdentityCheck> findReusable(
      String tenantId, String documentType, String documentDigits, String name, Instant notBefore);
```

- [ ] **Passo 4: implementar a query JPA**

Em `IdentityCheckJpaRepository`:

```java
  @Query(
      """
      SELECT c FROM IdentityCheckEntity c
       WHERE c.tenantId = :tenantId
         AND c.documentType = :documentType
         AND c.documentDigits = :documentDigits
         AND c.name = :name
         AND c.checkedAt >= :notBefore
         AND c.reusedFromId IS NULL
         AND c.status IN :statuses
       ORDER BY c.checkedAt DESC
       LIMIT 1
      """)
  Optional<IdentityCheckEntity> findReusable(
      @Param("tenantId") String tenantId,
      @Param("documentType") String documentType,
      @Param("documentDigits") String documentDigits,
      @Param("name") String name,
      @Param("notBefore") Instant notBefore,
      @Param("statuses") Collection<IdentityStatus> statuses);
```

Em `IdentityCheckRepositoryImpl`, passar a lista de desfechos definitivos e mapear para o
domínio:

```java
  /**
   * Desfechos que podem ser reaproveitados. {@code UNAVAILABLE} fica de fora: reusá-lo congelaria
   * uma indisponibilidade passada como se fosse resposta do bureau, e a avaliação seguinte herdaria
   * um REVIEW que talvez não fosse mais verdade.
   */
  private static final Set<IdentityStatus> REUSABLE =
      Set.of(
          IdentityStatus.VERIFIED,
          IdentityStatus.NOT_FOUND,
          IdentityStatus.MISMATCH,
          IdentityStatus.DECEASED);

  @Override
  public Optional<IdentityCheck> findReusable(
      String tenantId, String documentType, String documentDigits, String name, Instant notBefore) {
    return jpa.findReusable(tenantId, documentType, documentDigits, name, notBefore, REUSABLE)
        .map(this::toDomain);
  }
```

Conferir os nomes das constantes contra `IdentityStatus` antes de compilar; se algum desfecho
definitivo tiver outro nome, ajustar o conjunto (nunca incluir `UNAVAILABLE`).

- [ ] **Passo 5: rodar e ver passar**

```bash
./mvnw -pl services/risk-engine test -Dtest=IdentityCheckReuseQueryIT
```

Esperado: 4 testes PASS.

- [ ] **Passo 6: commit**

```bash
git add services/risk-engine/src/main/java/com/barrier/riskengine/identity/repository/ \
        services/risk-engine/src/test/java/com/barrier/riskengine/identity/IdentityCheckReuseQueryIT.java
git commit -m "feat(identity): consulta de verificacao reaproveitavel"
```

---

### Task 3: `IdentityService` consulta o reuso antes da rede

**Files:**
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/identity/service/VerifyIdentityCommand.java`
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/identity/service/IdentityService.java`
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/assessment/service/AssessmentProcessor.java`
- Modify: `services/risk-engine/src/main/resources/application.yml`
- Test: `services/risk-engine/src/test/java/com/barrier/riskengine/identity/IdentityServiceReuseTest.java`

**Interfaces:**
- Consome: `findReusable(...)` da Task 2, `IdentityCheck.reusing(...)` da Task 1.
- Produz: `VerifyIdentityCommand(String assessmentId, String tenantId, String documentType, String documentDigits, String name)` — **componente `tenantId` novo, na segunda posição**.

- [ ] **Passo 1: escrever os testes (vão falhar)**

```java
  @Test
  void reaproveitaCheckRecenteSemChamarOBureau() {
    IdentityCheck anterior = verificado("aval-1");
    when(repository.findReusable(eq("tenant-a"), eq("CPF"), eq(CPF), eq(NOME), any()))
        .thenReturn(Optional.of(anterior));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    IdentityResult resultado = service(true).verify(comando());

    verify(provider, never()).check(any());
    assertThat(resultado.check().status()).isEqualTo(IdentityStatus.VERIFIED);
    assertThat(resultado.check().reusedFromId()).isEqualTo(anterior.id());
  }

  @Test
  void perfilNaoAcompanhaOReuso() {
    when(repository.findReusable(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(verificado("aval-1")));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    IdentityResult resultado = service(true).verify(comando());

    assertThat(resultado.company()).isNull();
    assertThat(resultado.person()).isNull();
  }

  @Test
  void desligadoVaiAoBureauMesmoComCheckRecente() {
    when(provider.check(any())).thenReturn(BureauResult.match("ok"));

    service(false).verify(comando());

    verify(provider).check(any());
    verify(repository, never()).findReusable(any(), any(), any(), any(), any());
  }

  @Test
  void cnpjNuncaReaproveita() {
    when(provider.check(any())).thenReturn(BureauResult.match("ok"));

    service(true)
        .verify(new VerifyIdentityCommand("aval-2", "tenant-a", "CNPJ", "11222333000181", NOME));

    verify(provider).check(any());
    verify(repository, never()).findReusable(any(), any(), any(), any(), any());
  }
```

Fábricas auxiliares, no estilo de `RescreeningServiceTest` (construtor direto, sem contexto
Spring):

```java
  private static final String CPF = "11144477735";
  private static final String NOME = "MARIA SILVA";

  @Mock BureauProvider provider;
  @Mock IdentityCheckRepository repository;

  private IdentityService service(boolean reuseEnabled) {
    when(provider.supports("CPF")).thenReturn(true);
    return new IdentityService(
        List.of(provider), repository, new CircuitBreakerRegistry(5, Duration.ofSeconds(30)),
        reuseEnabled, Duration.ofHours(24));
  }

  private static VerifyIdentityCommand comando() {
    return new VerifyIdentityCommand("aval-2", "tenant-a", "CPF", CPF, NOME);
  }

  private static IdentityCheck verificado(String assessmentId) {
    return IdentityCheck.create(
        assessmentId, "tenant-a", "CPF", CPF, NOME,
        IdentityStatus.VERIFIED, "bigboost", "titular regular", "query-99", "{}");
  }
```

`BureauResult.match(String detail)` existe e é a fábrica correta. Conferir a assinatura de
`CircuitBreakerRegistry` e o método real de seleção de provider por tipo (`supports`) contra o
código antes de rodar — se divergirem, ajustar o stub, não o teste.

- [ ] **Passo 2: rodar e ver falhar**

```bash
./mvnw -pl services/risk-engine test -Dtest=IdentityServiceReuseTest
```

Esperado: falha de compilação — `VerifyIdentityCommand` ainda não tem `tenantId`.

- [ ] **Passo 3: acrescentar `tenantId` ao comando**

```java
public record VerifyIdentityCommand(
    String assessmentId, String tenantId, String documentType, String documentDigits, String name) {}
```

Atualizar `AssessmentProcessor` para passar `assessment.tenantId()` (já disponível ali — é o
mesmo valor que alimenta `RiskContext.tenantId`), e os testes existentes que constroem o
comando.

- [ ] **Passo 4: implementar o reuso no service**

Injetar a configuração:

```java
  private final boolean reuseEnabled;
  private final Duration reuseTtl;

  // no construtor:
  @Value("${barrier.identity.reuse.enabled:false}") boolean reuseEnabled,
  @Value("${barrier.identity.reuse.ttl:PT24H}") Duration reuseTtl
```

E, como primeira coisa dentro de `verify(...)`, antes de montar a cadeia:

```java
    Optional<IdentityCheck> reusable = findReusable(command);
    if (reusable.isPresent()) {
      IdentityCheck original = reusable.get();
      IdentityCheck check = repository.save(IdentityCheck.reusing(command.assessmentId(), original));
      log.info(
          "Identidade de {} {} reaproveitada da consulta {} de {} (sem chamada ao bureau)",
          command.documentType(),
          Documents.mask(command.documentDigits()),
          original.id(),
          original.checkedAt());
      // Perfil não acompanha: CompanyProfile/PersonProfile são transientes e não ficam no check.
      // Para PF isso é aceitável — o SubjectProfile já foi enriquecido pela consulta original e o
      // patch preserva campo ausente. Para PJ não seria: a CorporateStructureRiskRule perderia o
      // QSA e deixaria de disparar em silêncio. É por isso que CNPJ não entra aqui.
      return new IdentityResult(check, null, null);
    }
```

E o método de elegibilidade:

```java
  /**
   * Só CPF, só com a flag ligada. O recorte por tipo de documento não é cautela genérica: é a
   * consequência de o perfil do bureau não ser persistido no check (ver o comentário acima).
   */
  private Optional<IdentityCheck> findReusable(VerifyIdentityCommand command) {
    if (!reuseEnabled || !"CPF".equals(command.documentType())) {
      return Optional.empty();
    }
    return repository.findReusable(
        command.tenantId(),
        command.documentType(),
        command.documentDigits(),
        command.name(),
        Instant.now().minus(reuseTtl));
  }
```

Ajustar o método `save(...)` já existente para preencher os campos novos (`tenantId`,
`documentType`, `documentDigits`, `name`) a partir do comando — sem isso, os checks gravados
hoje nunca serão elegíveis a reuso amanhã.

- [ ] **Passo 5: configuração**

Em `application.yml`, sob `barrier.identity`:

```yaml
    reuse:
      # Desligado por padrão: ligar muda de onde vem a evidência de uma decisão, e isso é
      # decisão de produto, não default de framework.
      enabled: false
      ttl: PT24H
```

- [ ] **Passo 6: rodar e ver passar**

```bash
./mvnw -pl services/risk-engine test -Dtest=IdentityServiceReuseTest
```

Esperado: 4 testes PASS.

- [ ] **Passo 7: suíte inteira**

```bash
./mvnw -pl services/risk-engine test
```

Esperado: verde. O `AssessmentProcessor` e todos os testes que montam `VerifyIdentityCommand`
foram tocados no Passo 3 — se algum ficou para trás, aparece aqui.

- [ ] **Passo 8: commit**

```bash
git add services/risk-engine/src/main/java/com/barrier/riskengine/identity/ \
        services/risk-engine/src/main/java/com/barrier/riskengine/assessment/service/AssessmentProcessor.java \
        services/risk-engine/src/main/resources/application.yml \
        services/risk-engine/src/test/java/com/barrier/riskengine/identity/IdentityServiceReuseTest.java
git commit -m "feat(identity): reuso de verificacao recente por documento e tenant"
```

---

### Task 4: Métrica de economia e a trilha na API

Sem contador, "o reuso está funcionando" é afirmação nossa sobre nós mesmos — o mesmo problema
que a V031 resolveu para o bureau.

**Files:**
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/identity/service/IdentityService.java`
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/assessment/controller/dto/AssessmentResponse.java`
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/assessment/controller/dto/AssessmentDtoMapper.java`
- Test: `services/risk-engine/src/test/java/com/barrier/riskengine/identity/IdentityReuseMetricsTest.java`

**Interfaces:**
- Consome: `IdentityCheck.isReused()` da Task 1.
- Produz: contadores `barrier_identity_check_total{outcome="fresh"|"reused"}`.

- [ ] **Passo 1: escrever o teste (vai falhar)**

```java
  @Test
  void contaReusoEConsultaFrescaSeparadamente() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    IdentityService service = service(registry, true);

    when(repository.findReusable(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(verificado("aval-1")));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    service.verify(comando());

    assertThat(registry.counter("barrier.identity.check", "outcome", "reused").count())
        .isEqualTo(1.0);
    assertThat(registry.counter("barrier.identity.check", "outcome", "fresh").count())
        .isEqualTo(0.0);
  }
```

- [ ] **Passo 2: rodar e ver falhar**

```bash
./mvnw -pl services/risk-engine test -Dtest=IdentityReuseMetricsTest
```

Esperado: FAIL — contador em zero (a métrica não existe).

- [ ] **Passo 3: implementar**

Injetar `MeterRegistry` no construtor do `IdentityService` (mesmo padrão de `AssessmentMetrics`,
que faz `registry.counter("barrier.assessment.processing.failures").increment()`) e contar os dois
desfechos:

```java
  /**
   * Conta de onde veio a verificação. Sem separar reuso de consulta fresca, uma queda de custo é
   * indistinguível de uma queda de tráfego — e uma flag de reuso ligada por engano numa base
   * grande não apareceria em lugar nenhum.
   */
  private void countCheck(String outcome) {
    registry.counter("barrier.identity.check", "outcome", outcome).increment();
  }
```

Chamar `countCheck("reused")` logo antes do `return` do caminho de reuso, e `countCheck("fresh")`
no `return` que segue a resposta de um provider. `UNAVAILABLE` no fim da cadeia não conta em
nenhum dos dois: não houve verificação.

- [ ] **Passo 4: expor a procedência no `GET`**

No `AssessmentResponse`, dentro do bloco de identidade, acrescentar `identityReused` (boolean) e
`identityCheckedAt` (o `checkedAt` da consulta **original**, seguindo `reusedFromId` quando
houver). Sem isso, o parceiro que recebe um `APROVADO` não tem como saber que a verificação é de
ontem — e é informação que ele precisa para a própria trilha dele.

- [ ] **Passo 5: rodar e ver passar**

```bash
./mvnw -pl services/risk-engine test -Dtest=IdentityReuseMetricsTest
./mvnw -pl services/risk-engine test
```

Esperado: PASS nos dois.

- [ ] **Passo 6: commit**

```bash
git add services/risk-engine/src/main/java/com/barrier/riskengine/ \
        services/risk-engine/src/test/java/com/barrier/riskengine/identity/IdentityReuseMetricsTest.java
git commit -m "feat(identity): metrica de reuso e procedencia da verificacao no GET"
```

---

### Task 5: Documentação

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/implementation/plano-remediacao-auditoria.md`
- Modify: `docs/architecture/kyc-flow.md`

- [ ] **Passo 1: registrar o estado no CLAUDE.md**

Parágrafo novo, no estilo dos existentes: o que é, o recorte de CPF e o porquê (perfil de PJ
transiente), o TTL, o escopo por tenant, a flag e o que `reused_from_id` garante.

- [ ] **Passo 2: cruzar com o plano de remediação**

Na Onda 3, sob "Ingestão em massa não tem cota nem isolamento", anotar que o reuso reduz o custo
por avaliação repetida mas **não substitui a cota** — reprocessar 500 mil documentos *distintos*
continua custando R$20 mil, porque nenhum deles tem consulta anterior. Reuso ataca repetição,
cota ataca volume; são controles diferentes para riscos diferentes, e confundi-los daria a
sensação falsa de que o item está fechado.

- [ ] **Passo 3: atualizar o fluxo**

Em `kyc-flow.md`, incluir a decisão de reuso antes da cadeia de bureaus.

- [ ] **Passo 4: commit**

```bash
git add CLAUDE.md docs/
git commit -m "docs: reuso de verificacao de identidade"
```

---

## Critério de pronto

- Duas avaliações do mesmo CPF, mesmo nome e mesmo tenant dentro de 24h fazem **uma** chamada
  ao bureau, e a segunda tem `reused_from_id` apontando para a primeira.
- Trocar o tenant, o nome, o tipo de documento, ou passar de 24h força consulta nova — cada um
  coberto por teste.
- `UNAVAILABLE` nunca é reaproveitado.
- CNPJ nunca é reaproveitado, e o motivo está escrito no código, não só aqui.
- `barrier_identity_check_total{outcome="reused"}` sobe; o `GET` mostra `identityReused` e a data
  da consulta original.
- `./mvnw test` verde com a flag ligada **e** desligada.

## Fora de escopo (entregas próprias)

- **Reuso de CNPJ** — exige reidratar `CompanyProfile` a partir de `raw_response`, ou persistir o
  perfil no check. Sem isso, é fail-open na `CorporateStructureRiskRule`.
- **Reuso entre tenants** — precisa de ADR (ver ADR-0012).
- **Cota de ingestão por tenant** — controle diferente, já mapeado no
  [ADR-0015](../adr/0015-ingestao-em-massa-faixa-separada.md).
- **Expurgo de `raw_response`** — o reuso aumenta a vida útil do dado bruto; retenção continua
  sendo pendência da Fase 6.
