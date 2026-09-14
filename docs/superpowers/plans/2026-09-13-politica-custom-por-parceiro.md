# Política de risco custom por parceiro — plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** O time de risco de um parceiro escreve regras próprias e as ativa sem deploy, e essas regras só podem endurecer a decisão do motor, nunca afrouxá-la.

**Architecture:** A política é uma árvore de predicados tipada sobre um catálogo de campos versionado, compilada e validada na escrita, guardada como artefato imutável e versionado. Cada regra do parceiro vira um `RiskRule` que entra no mesmo stream das regras de código do motor, produzindo `RiskResult` com evidência gerada pela caminhada da árvore. A garantia de que regra custom só endurece vem da monotonicidade que `ScoreAggregation` já tem, preservada por uma trava de score não negativo na compilação.

**Tech Stack:** Java 25 · Spring Boot 4.0 · PostgreSQL + Flyway · JPA/Hibernate (`@JdbcTypeCode(SqlTypes.JSON)`) · Jackson 3 (`tools.jackson.*`) · JUnit 5 + AssertJ + Testcontainers + ArchUnit · springdoc 3.0.0

**Spec:** [docs/superpowers/specs/2026-09-13-politica-de-risco-custom-por-parceiro-design.md](../specs/2026-09-13-politica-de-risco-custom-por-parceiro-design.md)

## Global Constraints

- Camadas `controller → service → repository`; integração externa só por interface `client`. Validado por ArchUnit.
- Pacote raiz do módulo novo: `com.barrier.riskengine.riskpolicy`.
- A interface `CustomRuleSource` é declarada em `com.barrier.riskengine.risk.rule.interfaces`, **não** em `riskpolicy`. Declarar do lado da implementação fecharia o ciclo `risk → riskpolicy → risk`.
- Migrations Flyway são imutáveis. A primeira migration livre é **V049** (V048 é a última existente).
- `ENGINE_VERSION` sobe para `barrier-risk-rules/1.9.0` neste plano, com comentário explicando a razão, no padrão das versões anteriores em `RiskScoringService`.
- Código de regra custom: `CUSTOM_` seguido de `[A-Z0-9_]+`.
- Literal de duração em ISO-8601 (`P6M`, `P18Y`).
- Nunca logar nem gravar em evidência CPF/CNPJ, nome, telefone, e-mail ou logradouro. Campo do catálogo marcado `OUTCOME_ONLY` nunca aparece por valor.
- Bug corrigido vem com teste. Toda tarefa termina com teste passando e commit.
- **Docker precisa estar rodando** para os testes de integração; sem ele a suíte fica verde só na aparência.
- `JAVA_HOME` deve apontar para `C:\Users\leona\.jdks\corretto-25.0.3` antes de qualquer `./mvnw`.
- `./mvnw spotless:apply` **não roda no JDK 25**. Formatar à mão, seguindo o estilo dos arquivos vizinhos.

**Comando de teste padrão:**

```bash
./mvnw test -pl services/risk-engine -Dtest=NomeDaClasseDeTeste
```

**Suíte completa do módulo:**

```bash
./mvnw test -pl services/risk-engine
```

---

## Estrutura de arquivos

Módulo novo `services/risk-engine/src/main/java/com/barrier/riskengine/riskpolicy/`:

| Caminho | Responsabilidade |
|---|---|
| `domain/catalog/PolicyFieldType.java` | Tipos de campo (DATE, NUMBER, STRING, ENUM, BOOLEAN, LIST) |
| `domain/catalog/EvidenceExposure.java` | `BY_VALUE` / `OUTCOME_ONLY` |
| `domain/catalog/PolicyField.java` | Um campo do catálogo: id, tipo, insumo, exposição, extrator |
| `domain/catalog/FieldCatalog.java` | O catálogo v1 e sua versão |
| `domain/tree/Condition.java` | Interface selada da árvore + os cinco nós |
| `domain/tree/Operator.java` | Operadores e quais tipos cada um aceita |
| `domain/tree/Literal.java` | Literais tipados |
| `domain/PolicyRule.java` | Regra do parceiro: código, condição, score, severidade, recomendação |
| `domain/RiskPolicy.java` | Versão de política: tenant, domínio, versão, status, regras |
| `domain/PolicyDomain.java` | `ONBOARDING` (transacional entra em P3) |
| `domain/PolicyStatus.java` | `DRAFT`, `ACTIVE`, `ARCHIVED` |
| `service/ConditionEvaluator.java` | Caminha a árvore e devolve resultado + evidências |
| `service/PolicyCompiler.java` | As quatro travas; transforma árvore crua em política válida |
| `service/PolicyCompilationException.java` | Erro de compilação com campo, operador e trava violada |
| `service/ContextInputDerivation.java` | Deriva `requires()` da árvore |
| `service/CustomPolicyRiskRule.java` | Adaptador `PolicyRule` → `RiskRule` |
| `service/CustomRuleSourceImpl.java` | Implementa `CustomRuleSource`: lê a versão ativa e devolve adaptadores |
| `service/RiskPolicyService.java` | Ciclo de vida: criar versão, ativar, arquivar |
| `repository/interfaces/RiskPolicyRepository.java` | Sem método que reescreva versão ativada |
| `repository/RiskPolicyRepositoryImpl.java` | Implementação JPA |
| `repository/RiskPolicyEntity.java` | Mapeamento, `rules_json` em JSONB |
| `controller/RiskPolicyController.java` | `/v1/policies` |
| `controller/PolicyFieldController.java` | `/v1/policy-fields` |
| `controller/dto/` | Requests e responses |

Modificados:

| Caminho | Mudança |
|---|---|
| `risk/rule/context/RiskContext.java` | Ganha `referenceInstant` |
| `risk/rule/interfaces/CustomRuleSource.java` | **Criado** (mora em `risk`, por inversão) |
| `risk/rule/interfaces/CustomRules.java` | **Criado** (retorno da interface acima) |
| `risk/service/RiskScoringService.java` | Consome a fonte custom; `ENGINE_VERSION` 1.9.0 |
| `risk/domain/model/RiskDecision.java` | Ganha `policyVersion` |
| `risk/domain/model/RiskScore.java` | Persiste `policyVersion` |
| `assessment/service/AssessmentProcessor.java` | Preenche `referenceInstant` |
| `replay/service/ReplayContextRebuilder.java` | Preenche `referenceInstant` com o instante gravado |
| `db/migration/V049__risk_policies.sql` | **Criado** |

---

### Task 1: `referenceInstant` no `RiskContext`

Habilitador. Os operadores `OLDER_THAN` e `WITHIN_LAST` precisam de um "agora", e para o replay reproduzir uma decisão antiga esse instante tem que ser o **da decisão**, não o relógio de parede. Sem esta tarefa, replay de política com regra de data daria resultado diferente por motivo errado.

**Files:**
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/risk/rule/context/RiskContext.java`
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/assessment/service/AssessmentProcessor.java:296`
- Modify: `services/risk-engine/src/main/java/com/barrier/riskengine/replay/service/ReplayContextRebuilder.java:141`
- Modify: os 10 arquivos de teste que constroem `RiskContext` (localizar com o grep do Step 2)

**Interfaces:**
- Consumes: nada.
- Produces: `RiskContext.referenceInstant()` retornando `java.time.Instant`, nunca nulo.

- [ ] **Step 1: Escrever o teste que falha**

Criar `services/risk-engine/src/test/java/com/barrier/riskengine/risk/rule/context/RiskContextTest.java`:

```java
package com.barrier.riskengine.risk.rule.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RiskContextTest {

  @Test
  void carrega_o_instante_de_referencia_da_decisao() {
    Instant quando = Instant.parse("2026-03-01T10:00:00Z");
    RiskContext ctx =
        new RiskContext("a-1", "tenant-1", null, null, null, null, null, quando);

    assertThat(ctx.referenceInstant()).isEqualTo(quando);
  }

  @Test
  void recusa_instante_nulo_porque_regra_de_data_nao_teria_relogio() {
    assertThatThrownBy(
            () -> new RiskContext("a-1", "tenant-1", null, null, null, null, null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("referenceInstant");
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
./mvnw test -pl services/risk-engine -Dtest=RiskContextTest
```

Esperado: erro de compilação, `constructor RiskContext cannot be applied to given types`.

Localizar todos os pontos de construção antes de seguir:

```bash
grep -rn "new RiskContext(" services/risk-engine/src --include=*.java
```

- [ ] **Step 3: Acrescentar o componente**

Em `RiskContext.java`, acrescentar o componente ao final do record e o compact constructor:

```java
public record RiskContext(
    String assessmentId,
    String tenantId,
    IdentityCheck identity,
    ScreeningResult screening,
    CompanyProfile company,
    SubjectProfile profile,
    AssuranceSummary assurance,
    java.time.Instant referenceInstant) {

  public RiskContext {
    java.util.Objects.requireNonNull(referenceInstant, "referenceInstant");
  }
}
```

Acrescentar ao Javadoc do record:

```java
 * @param referenceInstant instante que a política usa como "agora" nos operadores de data. É o
 *     instante da decisão, nunca o relógio de parede: sem isso, o replay de uma decisão antiga
 *     calcularia idade e janela contra hoje e reportaria como mudança de motor o que é só o tempo
 *     passando. Diferente dos demais insumos, está sempre disponível e por isso não tem
 *     {@link ContextInput} correspondente.
```

- [ ] **Step 4: Preencher os dois pontos de produção**

Em `AssessmentProcessor.java:296`, acrescentar como último argumento o instante da avaliação em curso. Usar o mesmo relógio que o processador já usa para concluir a avaliação; se ele não tiver um à mão, `Instant.now()` no início do método de processamento, guardado em variável local e reutilizado, para que todas as regras da mesma avaliação enxerguem o mesmo instante.

Em `ReplayContextRebuilder.java:141`, acrescentar o instante **gravado** da decisão que está sendo replayada, não `Instant.now()`. O reconstrutor já carrega o registro de `risk_scores`; usar o campo de data de avaliação dele.

- [ ] **Step 5: Consertar os testes existentes**

Para cada arquivo listado no grep do Step 2, acrescentar o argumento. Onde o teste não se importa com data, usar uma constante legível no próprio arquivo de teste:

```java
private static final Instant QUANDO = Instant.parse("2026-01-01T00:00:00Z");
```

- [ ] **Step 6: Rodar a suíte do módulo**

```bash
./mvnw test -pl services/risk-engine
```

Esperado: PASS. Nenhum comportamento mudou; só o tipo ficou mais explícito.

- [ ] **Step 7: Commit**

```bash
git add services/risk-engine/src
git commit -m "refactor(risk): RiskContext carrega o instante de referencia da decisao

Operador de data precisa de um 'agora', e para replay reproduzir uma decisao
antiga esse instante tem que ser o da decisao, nao o relogio de parede. Sem
isso, replay de regra com janela reportaria como mudanca de motor o que e so o
tempo passando.

Habilitador da politica custom por parceiro; nenhum comportamento mudou.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Catálogo de campos

O catálogo é a interface pública da linguagem e o que a desacopla do formato do `RiskContext`. Quando o domínio transacional chegar (P3), o catálogo cresce e a linguagem não muda.

**Files:**
- Create: `riskpolicy/domain/catalog/PolicyFieldType.java`
- Create: `riskpolicy/domain/catalog/EvidenceExposure.java`
- Create: `riskpolicy/domain/catalog/PolicyField.java`
- Create: `riskpolicy/domain/catalog/FieldCatalog.java`
- Test: `riskpolicy/domain/catalog/FieldCatalogTest.java`

**Interfaces:**
- Consumes: `RiskContext` (Task 1), `ContextInput`.
- Produces: `FieldCatalog.V1` (constante), `FieldCatalog.version()` (int), `FieldCatalog.find(String id)` devolvendo `Optional<PolicyField>`, `FieldCatalog.all()` devolvendo `List<PolicyField>`. `PolicyField.resolve(Object raiz)` devolvendo `Object` (pode ser nulo).

- [ ] **Step 1: Escrever o teste que falha**

```java
package com.barrier.riskengine.riskpolicy.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldCatalogTest {

  private static final Instant QUANDO = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void resolve_campo_de_topo_a_partir_do_contexto() {
    CompanyProfile empresa =
        new CompanyProfile(LocalDate.of(2026, 4, 1), "6499-9/99", "Outras atividades", List.of());
    RiskContext ctx = new RiskContext("a-1", "t-1", null, null, empresa, null, null, QUANDO);

    PolicyField campo = FieldCatalog.V1.find("company.openingDate").orElseThrow();

    assertThat(campo.resolve(ctx)).isEqualTo(LocalDate.of(2026, 4, 1));
    assertThat(campo.type()).isEqualTo(PolicyFieldType.DATE);
    assertThat(campo.input()).isEqualTo(ContextInput.COMPANY);
  }

  @Test
  void resolve_campo_de_elemento_a_partir_do_socio() {
    CompanyProfile.Partner socio = new CompanyProfile.Partner("ACME BV", true, true, "Sócio");

    PolicyField campo = FieldCatalog.V1.find("company.partners[].foreign").orElseThrow();

    assertThat(campo.resolve(socio)).isEqualTo(true);
    assertThat(campo.parentListId()).isEqualTo("company.partners");
  }

  @Test
  void campo_ausente_resolve_para_nulo_sem_estourar() {
    RiskContext semEmpresa = new RiskContext("a-1", "t-1", null, null, null, null, null, QUANDO);

    assertThat(FieldCatalog.V1.find("company.openingDate").orElseThrow().resolve(semEmpresa))
        .isNull();
  }

  @Test
  void campos_de_pii_direto_nao_estao_no_catalogo() {
    List<String> proibidos =
        List.of(
            "identity.documentDigits",
            "identity.name",
            "identity.rawResponse",
            "screening.hits[].matchedName",
            "company.partners[].name",
            "profile.phone",
            "profile.email",
            "profile.address.street",
            "profile.legalRepresentativeDocument");

    assertThat(proibidos).allSatisfy(id -> assertThat(FieldCatalog.V1.find(id)).isEmpty());
  }

  @Test
  void campos_sensiveis_presentes_sao_marcados_para_nao_vazar_valor() {
    assertThat(FieldCatalog.V1.find("profile.birthDate").orElseThrow().exposure())
        .isEqualTo(EvidenceExposure.OUTCOME_ONLY);
    assertThat(FieldCatalog.V1.find("profile.declaredIncome").orElseThrow().exposure())
        .isEqualTo(EvidenceExposure.OUTCOME_ONLY);
  }

  @Test
  void todo_campo_de_elemento_aponta_para_uma_lista_que_existe_no_catalogo() {
    assertThat(FieldCatalog.V1.all())
        .filteredOn(campo -> campo.parentListId() != null)
        .allSatisfy(
            campo -> {
              PolicyField lista = FieldCatalog.V1.find(campo.parentListId()).orElseThrow();
              assertThat(lista.type()).isEqualTo(PolicyFieldType.LIST);
            });
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
./mvnw test -pl services/risk-engine -Dtest=FieldCatalogTest
```

Esperado: erro de compilação, pacote `riskpolicy.domain.catalog` não existe.

- [ ] **Step 3: Implementar os tipos**

`PolicyFieldType.java`:

```java
package com.barrier.riskengine.riskpolicy.domain.catalog;

/** Tipo de um campo do catálogo. Decide quais operadores são válidos sobre ele. */
public enum PolicyFieldType {
  DATE,
  NUMBER,
  STRING,
  ENUM,
  BOOLEAN,
  LIST
}
```

`EvidenceExposure.java`:

```java
package com.barrier.riskengine.riskpolicy.domain.catalog;

/**
 * Se o <b>valor</b> do campo pode aparecer na evidência da regra.
 *
 * <p>Evidência de regra viaja para dentro de {@code evaluated_json}, volta no {@code GET} da
 * avaliação e entra no dossiê de replay — lugares cujo controle de acesso é mais fraco que o da
 * coluna original. Campo {@link #OUTCOME_ONLY} registra apenas o resultado da comparação, nunca o
 * valor comparado. É a mesma regra que permite que um alerta do módulo {@code monitoring} circule
 * em canal externo.
 */
public enum EvidenceExposure {
  BY_VALUE,
  OUTCOME_ONLY
}
```

`PolicyField.java`:

```java
package com.barrier.riskengine.riskpolicy.domain.catalog;

import com.barrier.riskengine.risk.rule.context.ContextInput;
import java.util.function.Function;

/**
 * Um campo que a política pode referenciar.
 *
 * @param id identificador estável que o parceiro escreve ({@code company.openingDate})
 * @param type tipo, que decide os operadores válidos
 * @param input insumo do {@code RiskContext} de onde este campo sai — é daqui que o
 *     {@code requires()} da política é derivado, em vez de declarado por humano
 * @param exposure se o valor pode aparecer na evidência
 * @param parentListId {@code null} para campo de topo; para campo de elemento, o id da lista a que
 *     ele pertence. É o que permite a compilação recusar {@code partners[].foreign} fora de um
 *     {@code AnyOf} sobre {@code company.partners}
 * @param extractor extrai o valor da raiz — o {@code RiskContext} para campo de topo, o elemento da
 *     lista para campo de elemento
 */
public record PolicyField(
    String id,
    PolicyFieldType type,
    ContextInput input,
    EvidenceExposure exposure,
    String parentListId,
    Function<Object, Object> extractor) {

  /** Resolve o valor; devolve {@code null} quando o caminho não existe no contexto. */
  public Object resolve(Object root) {
    if (root == null) {
      return null;
    }
    return extractor.apply(root);
  }

  public boolean isElementField() {
    return parentListId != null;
  }
}
```

- [ ] **Step 4: Implementar o catálogo v1**

`FieldCatalog.java` — construir com um builder privado para manter as 27 linhas legíveis. Cada campo de topo recebe um extrator que faz cast de `Object` para `RiskContext` e navega com proteção contra nulo; cada campo de elemento faz cast para o tipo do elemento.

Padrão dos extratores de topo:

```java
private static Function<Object, Object> ctx(Function<RiskContext, Object> f) {
  return raiz -> f.apply((RiskContext) raiz);
}
```

Exemplos que devem existir exatamente com estes ids (a lista completa está na §5.1 do spec):

```java
campo("identity.status", ENUM, ContextInput.IDENTITY, BY_VALUE,
    ctx(c -> c.identity() == null ? null : c.identity().status()));

campo("company.openingDate", DATE, ContextInput.COMPANY, BY_VALUE,
    ctx(c -> c.company() == null ? null : c.company().openingDate()));

campo("company.partners", LIST, ContextInput.COMPANY, BY_VALUE,
    ctx(c -> c.company() == null ? List.of() : c.company().partners()));

campoDeElemento("company.partners[].foreign", BOOLEAN, ContextInput.COMPANY, BY_VALUE,
    "company.partners", e -> ((CompanyProfile.Partner) e).foreign());

campo("profile.birthDate", DATE, ContextInput.PROFILE, OUTCOME_ONLY,
    ctx(c -> c.profile() == null ? null : c.profile().birthDate()));
```

Lista que retorna vazio em vez de nulo quando o insumo falta: é o que faz `AnyOf` sobre empresa sem QSA avaliar como falso em vez de estourar.

A versão do catálogo é constante:

```java
/** Incrementa a cada campo adicionado ou marcado obsoleto. Política grava contra qual versão foi escrita. */
public static final int VERSION = 1;
```

- [ ] **Step 5: Rodar e ver passar**

```bash
./mvnw test -pl services/risk-engine -Dtest=FieldCatalogTest
```

Esperado: PASS, 6 testes.

- [ ] **Step 6: Commit**

```bash
git add services/risk-engine/src
git commit -m "feat(riskpolicy): catalogo de campos versionado como interface da politica

O catalogo e o que desacopla a linguagem do formato do RiskContext: quando o
dominio transacional chegar, ele cresce e a linguagem nao muda. Cada campo
declara o ContextInput de origem, e e dai que o requires() da politica sai
derivado em vez de declarado por humano.

Campo marcado OUTCOME_ONLY nunca tem o valor escrito na evidencia, e PII direto
(documento, nome, telefone, logradouro) fica fora do catalogo, com teste que
falha se alguem adicionar.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Árvore de predicados e avaliação

**Files:**
- Create: `riskpolicy/domain/tree/Condition.java`
- Create: `riskpolicy/domain/tree/Operator.java`
- Create: `riskpolicy/domain/tree/Literal.java`
- Create: `riskpolicy/service/ConditionEvaluator.java`
- Test: `riskpolicy/service/ConditionEvaluatorTest.java`

**Interfaces:**
- Consumes: `PolicyField`, `FieldCatalog` (Task 2), `RiskContext.referenceInstant()` (Task 1).
- Produces: `ConditionEvaluator.evaluate(Condition, RiskContext)` devolvendo `ClauseEvaluation(boolean matched, List<String> evidences)`.

- [ ] **Step 1: Escrever o teste que falha**

```java
package com.barrier.riskengine.riskpolicy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConditionEvaluatorTest {

  private static final Instant AGORA = Instant.parse("2026-09-01T00:00:00Z");
  private final ConditionEvaluator evaluator = new ConditionEvaluator();

  private RiskContext comEmpresa(LocalDate abertura, CompanyProfile.Partner... socios) {
    CompanyProfile empresa = new CompanyProfile(abertura, "6499-9/99", "desc", List.of(socios));
    return new RiskContext("a-1", "t-1", null, null, empresa, null, null, AGORA);
  }

  private Condition comparacao(String campo, Operator op, Literal valor) {
    return new Condition.Comparison(FieldCatalog.V1.find(campo).orElseThrow(), op, valor);
  }

  @Test
  void empresa_aberta_dentro_da_janela_casa() {
    Condition c =
        comparacao(
            "company.openingDate", Operator.WITHIN_LAST, Literal.duration(Period.ofMonths(6)));

    assertThat(evaluator.evaluate(c, comEmpresa(LocalDate.of(2026, 7, 1))).matched()).isTrue();
    assertThat(evaluator.evaluate(c, comEmpresa(LocalDate.of(2020, 1, 1))).matched()).isFalse();
  }

  @Test
  void any_of_acha_socio_estrangeiro_pj() {
    Condition each =
        new Condition.And(
            List.of(
                comparacao("company.partners[].foreign", Operator.EQ, Literal.bool(true)),
                comparacao("company.partners[].legalEntity", Operator.EQ, Literal.bool(true))));
    Condition c =
        new Condition.AnyOf(FieldCatalog.V1.find("company.partners").orElseThrow(), each);

    RiskContext comHolding =
        comEmpresa(
            LocalDate.of(2020, 1, 1), new CompanyProfile.Partner("ACME BV", true, true, "Sócio"));
    RiskContext soPessoaFisica =
        comEmpresa(
            LocalDate.of(2020, 1, 1), new CompanyProfile.Partner("Fulano", false, false, "Sócio"));

    assertThat(evaluator.evaluate(c, comHolding).matched()).isTrue();
    assertThat(evaluator.evaluate(c, soPessoaFisica).matched()).isFalse();
  }

  @Test
  void any_of_sobre_lista_vazia_e_falso_e_nao_estoura() {
    Condition c =
        new Condition.AnyOf(
            FieldCatalog.V1.find("company.partners").orElseThrow(),
            comparacao("company.partners[].foreign", Operator.EQ, Literal.bool(true)));

    assertThat(evaluator.evaluate(c, comEmpresa(LocalDate.of(2020, 1, 1))).matched()).isFalse();
  }

  @Test
  void not_inverte() {
    Condition dentro =
        comparacao(
            "company.openingDate", Operator.WITHIN_LAST, Literal.duration(Period.ofMonths(6)));

    assertThat(evaluator.evaluate(new Condition.Not(dentro), comEmpresa(LocalDate.of(2020, 1, 1)))
            .matched())
        .isTrue();
  }

  @Test
  void campo_ausente_nao_casa_e_nao_estoura() {
    RiskContext semEmpresa = new RiskContext("a-1", "t-1", null, null, null, null, null, AGORA);
    Condition c =
        comparacao(
            "company.openingDate", Operator.WITHIN_LAST, Literal.duration(Period.ofMonths(6)));

    assertThat(evaluator.evaluate(c, semEmpresa).matched()).isFalse();
  }

  @Test
  void evidencia_traz_o_valor_de_campo_publico() {
    Condition c =
        comparacao(
            "company.openingDate", Operator.WITHIN_LAST, Literal.duration(Period.ofMonths(6)));

    assertThat(evaluator.evaluate(c, comEmpresa(LocalDate.of(2026, 7, 1))).evidences())
        .anySatisfy(e -> assertThat(e).contains("company.openingDate").contains("2026-07-01"));
  }

  @Test
  void evidencia_omite_valor_de_campo_sensivel() {
    // profile.birthDate é OUTCOME_ONLY: a evidência cita o campo e o operador, nunca a data.
    com.barrier.riskengine.subject.profile.domain.SubjectProfile perfil =
        SubjectProfileFixtures.comNascimento(LocalDate.of(1990, 5, 20));
    RiskContext ctx = new RiskContext("a-1", "t-1", null, null, null, perfil, null, AGORA);
    Condition c =
        comparacao("profile.birthDate", Operator.OLDER_THAN, Literal.duration(Period.ofYears(18)));

    var resultado = evaluator.evaluate(c, ctx);

    assertThat(resultado.matched()).isTrue();
    assertThat(resultado.evidences()).isNotEmpty();
    assertThat(resultado.evidences()).noneSatisfy(e -> assertThat(e).contains("1990"));
  }

  @Test
  void so_comparacao_verdadeira_entra_na_evidencia() {
    Condition c =
        new Condition.Or(
            List.of(
                comparacao(
                    "company.openingDate",
                    Operator.WITHIN_LAST,
                    Literal.duration(Period.ofMonths(6))),
                comparacao("company.cnaeCode", Operator.EQ, Literal.text("9999-9/99"))));

    var resultado = evaluator.evaluate(c, comEmpresa(LocalDate.of(2026, 7, 1)));

    assertThat(resultado.matched()).isTrue();
    assertThat(resultado.evidences()).noneSatisfy(e -> assertThat(e).contains("cnaeCode"));
  }
}
```

`SubjectProfileFixtures` é um helper de teste; se não existir um equivalente no módulo, criar em `services/risk-engine/src/test/java/com/barrier/riskengine/riskpolicy/service/SubjectProfileFixtures.java` com um único método estático que devolve um `SubjectProfile` com o `birthDate` pedido e o resto nulo.

- [ ] **Step 2: Rodar e ver falhar**

```bash
./mvnw test -pl services/risk-engine -Dtest=ConditionEvaluatorTest
```

Esperado: erro de compilação.

- [ ] **Step 3: Implementar a árvore**

`Condition.java`:

```java
package com.barrier.riskengine.riskpolicy.domain.tree;

import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyField;
import java.util.List;

/**
 * Árvore de predicados de uma regra de política.
 *
 * <p><b>Total por construção</b>: não há laço, recursão sobre dado nem chamada de função. Uma
 * política não tem como não terminar, o que é o que permite avaliá-la dentro do caminho de decisão
 * sem orçamento de tempo. Profundidade e número de nós têm teto, verificados na compilação.
 */
public sealed interface Condition {

  record And(List<Condition> operands) implements Condition {
    public And {
      operands = List.copyOf(operands);
    }
  }

  record Or(List<Condition> operands) implements Condition {
    public Or {
      operands = List.copyOf(operands);
    }
  }

  record Not(Condition operand) implements Condition {}

  record Comparison(PolicyField field, Operator op, Literal value) implements Condition {}

  /**
   * Verdadeiro quando <b>algum</b> elemento da lista satisfaz {@code each}.
   *
   * <p>Não existe nó {@code NoneOf}: é {@code Not(AnyOf(...))}. Lista vazia ou ausente avalia
   * falso, que é o que faz uma PJ sem QSA não casar em vez de estourar.
   */
  record AnyOf(PolicyField listField, Condition each) implements Condition {}
}
```

`Operator.java` — cada operador declara os tipos que aceita:

```java
package com.barrier.riskengine.riskpolicy.domain.tree;

import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyFieldType;
import java.util.Set;

public enum Operator {
  EQ(PolicyFieldType.values()),
  NEQ(PolicyFieldType.values()),
  IS_NULL(PolicyFieldType.values()),
  IS_NOT_NULL(PolicyFieldType.values()),
  IN(PolicyFieldType.STRING, PolicyFieldType.NUMBER, PolicyFieldType.ENUM),
  NOT_IN(PolicyFieldType.STRING, PolicyFieldType.NUMBER, PolicyFieldType.ENUM),
  STARTS_WITH(PolicyFieldType.STRING),
  LT(PolicyFieldType.NUMBER),
  LTE(PolicyFieldType.NUMBER),
  GT(PolicyFieldType.NUMBER),
  GTE(PolicyFieldType.NUMBER),
  BEFORE(PolicyFieldType.DATE),
  AFTER(PolicyFieldType.DATE),
  OLDER_THAN(PolicyFieldType.DATE),
  WITHIN_LAST(PolicyFieldType.DATE);

  private final Set<PolicyFieldType> supported;

  Operator(PolicyFieldType... tipos) {
    this.supported = Set.of(tipos);
  }

  public boolean supports(PolicyFieldType type) {
    return supported.contains(type);
  }
}
```

Nota: `LIST` nunca aparece em `Comparison`; lista só é usada por `AnyOf`. A compilação (Task 4) recusa `Comparison` sobre campo `LIST`.

`Literal.java` — selado, com fábricas:

```java
package com.barrier.riskengine.riskpolicy.domain.tree;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;

public sealed interface Literal {

  record Text(String value) implements Literal {}

  record Number(BigDecimal value) implements Literal {}

  record Bool(boolean value) implements Literal {}

  record Date(LocalDate value) implements Literal {}

  record Duration(Period value) implements Literal {}

  record TextSet(List<String> values) implements Literal {}

  /** Para {@code IS_NULL} e {@code IS_NOT_NULL}, que não comparam contra nada. */
  record None() implements Literal {}

  static Literal text(String v) { return new Text(v); }
  static Literal number(BigDecimal v) { return new Number(v); }
  static Literal bool(boolean v) { return new Bool(v); }
  static Literal date(LocalDate v) { return new Date(v); }
  static Literal duration(Period v) { return new Duration(v); }
  static Literal textSet(List<String> v) { return new TextSet(List.copyOf(v)); }
  static Literal none() { return new None(); }
}
```

- [ ] **Step 4: Implementar o avaliador**

`ConditionEvaluator.java`, um `@Component` sem estado:

- `evaluate(Condition, RiskContext)` devolve `ClauseEvaluation(boolean matched, List<String> evidences)`, um record aninhado público.
- Percorre com `switch` sobre a interface selada. O compilador garante exaustividade, então nó novo não passa despercebido.
- `And` / `Or`: avaliam **todos** os operandos e concatenam as evidências. Não fazer curto-circuito, de propósito: a evidência é o produto, e curto-circuitar esconderia metade do motivo. A árvore tem teto, então o custo é limitado.
- `Not`: inverte `matched` e **descarta** as evidências do operando. Evidência de cláusula negada diria o oposto do que aconteceu.
- `AnyOf`: resolve a lista (`List.of()` quando nula), avalia `each` para cada elemento resolvendo campos de elemento contra o elemento, e devolve as evidências apenas do **primeiro** elemento que casou.
- `Comparison`: resolve o campo; se o valor é nulo, o resultado é falso, exceto para `IS_NULL`. Comparações de data usam `context.referenceInstant()` convertido para `LocalDate` em UTC.
- Evidência é gerada **só para `Comparison` que avaliou verdadeiro**. Formato:
  - `BY_VALUE`: `company.openingDate=2026-07-01 WITHIN_LAST P6M`
  - `OUTCOME_ONLY`: `profile.birthDate OLDER_THAN P18Y (valor omitido)`

Para resolver campo de elemento, o avaliador precisa saber qual elemento está em escopo. Passar o elemento corrente como parâmetro interno:

```java
private ClauseEvaluation eval(Condition c, RiskContext ctx, Object elementoEmEscopo)
```

`Comparison` resolve contra `elementoEmEscopo` quando `field.isElementField()`, e contra `ctx` caso contrário.

- [ ] **Step 5: Rodar e ver passar**

```bash
./mvnw test -pl services/risk-engine -Dtest=ConditionEvaluatorTest
```

Esperado: PASS, 8 testes.

- [ ] **Step 6: Commit**

```bash
git add services/risk-engine/src
git commit -m "feat(riskpolicy): arvore de predicados total e avaliador com evidencia

Interface selada sem escapatoria: sem laco, sem recursao sobre dado, sem chamada
de funcao. Uma politica nao tem como nao terminar, o que e o que permite avalia-la
dentro do caminho de decisao.

AnyOf resolve lista, que e onde mora metade do KYB (socio estrangeiro no QSA).
Lista vazia ou ausente avalia falso em vez de estourar. NoneOf nao existe como no
proprio: e Not(AnyOf).

Evidencia sai so de comparacao verdadeira, e campo OUTCOME_ONLY entra sem o valor.
And/Or nao curto-circuitam de proposito: a evidencia e o produto.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Compilador de política e derivação de `requires()`

É aqui que o piso regulatório fica de pé.

**Files:**
- Create: `riskpolicy/domain/PolicyRule.java`
- Create: `riskpolicy/domain/RiskPolicy.java`
- Create: `riskpolicy/domain/PolicyDomain.java`
- Create: `riskpolicy/domain/PolicyStatus.java`
- Create: `riskpolicy/service/PolicyCompiler.java`
- Create: `riskpolicy/service/PolicyCompilationException.java`
- Create: `riskpolicy/service/ContextInputDerivation.java`
- Test: `riskpolicy/service/PolicyCompilerTest.java`
- Test: `riskpolicy/service/ContextInputDerivationTest.java`

**Interfaces:**
- Consumes: `Condition`, `Operator`, `Literal` (Task 3), `FieldCatalog` (Task 2), `RegulatoryRiskRules`.
- Produces: `PolicyCompiler.compile(List<PolicyRule>)` que lança `PolicyCompilationException` ou retorna as regras validadas. `ContextInputDerivation.of(Condition)` devolvendo `Set<ContextInput>`.

- [ ] **Step 1: Escrever o teste das quatro travas**

```java
package com.barrier.riskengine.riskpolicy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import java.time.Period;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyCompilerTest {

  private final PolicyCompiler compiler = new PolicyCompiler(FieldCatalog.V1);

  private Condition empresaNova() {
    return new Condition.Comparison(
        FieldCatalog.V1.find("company.openingDate").orElseThrow(),
        Operator.WITHIN_LAST,
        Literal.duration(Period.ofMonths(6)));
  }

  private PolicyRule regra(String codigo, int score) {
    return new PolicyRule(
        codigo, "Empresa nova", empresaNova(), score, Severity.MEDIUM, RiskRecommendation.REVIEW);
  }

  @Test
  void politica_valida_compila() {
    assertThatCode(() -> compiler.compile(List.of(regra("CUSTOM_EMPRESA_NOVA", 150))))
        .doesNotThrowAnyException();
  }

  @Test
  void trava_1_score_negativo_nao_compila() {
    assertThatThrownBy(() -> compiler.compile(List.of(regra("CUSTOM_AFROUXA", -100))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("score")
        .hasMessageContaining("CUSTOM_AFROUXA");
  }

  @Test
  void trava_2_codigo_fora_do_namespace_nao_compila() {
    assertThatThrownBy(() -> compiler.compile(List.of(regra("EMPRESA_NOVA", 150))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("CUSTOM_");
  }

  @Test
  void trava_2_codigo_de_regra_regulatoria_nao_compila_nem_com_prefixo() {
    assertThatThrownBy(() -> compiler.compile(List.of(regra("CUSTOM_SANCTION", 150))))
        .isInstanceOf(PolicyCompilationException.class);
    assertThatThrownBy(() -> compiler.compile(List.of(regra("SANCTION", 150))))
        .isInstanceOf(PolicyCompilationException.class);
  }

  @Test
  void trava_2_codigo_repetido_na_mesma_versao_nao_compila() {
    assertThatThrownBy(
            () ->
                compiler.compile(
                    List.of(regra("CUSTOM_DUPLICADO", 100), regra("CUSTOM_DUPLICADO", 200))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("CUSTOM_DUPLICADO");
  }

  @Test
  void trava_3_operador_invalido_para_o_tipo_do_campo_nao_compila() {
    Condition invalida =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.openingDate").orElseThrow(),
            Operator.STARTS_WITH,
            Literal.text("2026"));

    assertThatThrownBy(
            () ->
                compiler.compile(
                    List.of(
                        new PolicyRule(
                            "CUSTOM_X",
                            "x",
                            invalida,
                            100,
                            Severity.LOW,
                            null))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("STARTS_WITH")
        .hasMessageContaining("DATE");
  }

  @Test
  void trava_3_campo_de_elemento_fora_de_any_of_nao_compila() {
    Condition solto =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.partners[].foreign").orElseThrow(),
            Operator.EQ,
            Literal.bool(true));

    assertThatThrownBy(
            () ->
                compiler.compile(
                    List.of(new PolicyRule("CUSTOM_Y", "y", solto, 100, Severity.LOW, null))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("AnyOf");
  }

  @Test
  void trava_4_arvore_funda_demais_nao_compila() {
    Condition fundo = empresaNova();
    for (int i = 0; i < PolicyCompiler.MAX_DEPTH + 1; i++) {
      fundo = new Condition.Not(fundo);
    }

    Condition finalFundo = fundo;
    assertThatThrownBy(
            () ->
                compiler.compile(
                    List.of(new PolicyRule("CUSTOM_Z", "z", finalFundo, 100, Severity.LOW, null))))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("profundidade");
  }

  @Test
  void score_zero_compila_porque_regra_pode_so_recomendar() {
    assertThatCode(() -> compiler.compile(List.of(regra("CUSTOM_SO_REVISA", 0))))
        .doesNotThrowAnyException();
  }

  @Test
  void mensagem_de_erro_cita_campo_operador_e_trava() {
    Condition invalida =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.openingDate").orElseThrow(),
            Operator.STARTS_WITH,
            Literal.text("x"));

    assertThatThrownBy(
            () ->
                compiler.compile(
                    List.of(new PolicyRule("CUSTOM_W", "w", invalida, 1, Severity.LOW, null))))
        .satisfies(
            e ->
                assertThat(e.getMessage())
                    .contains("company.openingDate")
                    .contains("STARTS_WITH"));
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
./mvnw test -pl services/risk-engine -Dtest=PolicyCompilerTest
```

Esperado: erro de compilação.

- [ ] **Step 3: Implementar os tipos de domínio**

`PolicyDomain.java`:

```java
package com.barrier.riskengine.riskpolicy.domain;

/**
 * Domínio de decisão a que uma política se aplica.
 *
 * <p>Existe com um único valor de propósito: é o que faz o domínio transacional (P3) entrar como
 * valor novo em vez de migração de conceito.
 */
public enum PolicyDomain {
  ONBOARDING
}
```

`PolicyStatus.java`: `DRAFT`, `ACTIVE`, `ARCHIVED`. Javadoc registra que `SHADOW` entra em P2.

`PolicyRule.java`:

```java
package com.barrier.riskengine.riskpolicy.domain;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;

/**
 * Uma regra escrita pelo parceiro. É deliberadamente a forma de um {@code RiskResult}: nada no
 * motor precisa aprender vocabulário novo.
 *
 * @param score pontos somados quando a condição casa. Nunca negativo — é a trava que preserva a
 *     monotonicidade de {@code ScoreAggregation} e, com ela, a garantia de que regra de parceiro
 *     não afrouxa decisão do motor
 * @param recommendation {@code null} quando a regra apenas pontua
 */
public record PolicyRule(
    String code,
    String name,
    Condition when,
    int score,
    Severity severity,
    RiskRecommendation recommendation) {}
```

`RiskPolicy.java`: record com `id` (UUID), `tenantId`, `domain`, `version` (int), `status`, `catalogVersion` (int), `rules` (List<PolicyRule>), `createdBy`, `createdAt`, `activatedBy`, `activatedAt`, `archivedAt`. Métodos `activate(String by, Instant when)` e `archive(Instant when)` devolvendo nova instância, nunca mutando.

- [ ] **Step 4: Implementar o compilador**

`PolicyCompilationException extends RuntimeException`, com construtor que recebe a mensagem já formatada. A mensagem **tem que citar** o código da regra, o campo e o operador quando aplicáveis, e nomear a trava violada. Contrato que descreve mal o erro empurra o dev externo para o suporte.

`PolicyCompiler`, `@Component`, recebendo o `FieldCatalog` no construtor. Constantes públicas `MAX_DEPTH = 10` e `MAX_NODES = 200`. Aplica, em ordem:

1. `score >= 0`, por regra. Mensagem cita o código e o valor.
2. Código casa `^CUSTOM_[A-Z0-9_]+$`; não pertence a `RegulatoryRiskRules.codes()`; removido o prefixo `CUSTOM_`, o resto também não pertence a `RegulatoryRiskRules.codes()`; único na lista.
3. Caminhada da árvore: todo `PolicyField` citado existe no catálogo pelo id; `Comparison` nunca sobre campo `LIST`; `op.supports(field.type())`; o `Literal` é do tipo que o operador exige; campo de elemento só aparece dentro de um `AnyOf` cuja `listField.id()` é igual ao `parentListId` do campo.
4. Profundidade `<= MAX_DEPTH` e contagem de nós `<= MAX_NODES`.

- [ ] **Step 5: Escrever e implementar a derivação de `requires()`**

Teste `ContextInputDerivationTest`:

```java
@Test
void deriva_os_insumos_dos_campos_que_a_arvore_referencia() {
  Condition c =
      new Condition.And(
          List.of(
              new Condition.Comparison(
                  FieldCatalog.V1.find("company.openingDate").orElseThrow(),
                  Operator.WITHIN_LAST,
                  Literal.duration(Period.ofMonths(6))),
              new Condition.Comparison(
                  FieldCatalog.V1.find("profile.nationality").orElseThrow(),
                  Operator.NEQ,
                  Literal.text("BRASILEIRA"))));

  assertThat(ContextInputDerivation.of(c))
      .containsExactlyInAnyOrder(ContextInput.COMPANY, ContextInput.PROFILE);
}

@Test
void any_of_inclui_o_insumo_da_lista_e_o_dos_campos_de_elemento() {
  Condition c =
      new Condition.AnyOf(
          FieldCatalog.V1.find("company.partners").orElseThrow(),
          new Condition.Comparison(
              FieldCatalog.V1.find("company.partners[].foreign").orElseThrow(),
              Operator.EQ,
              Literal.bool(true)));

  assertThat(ContextInputDerivation.of(c)).containsExactly(ContextInput.COMPANY);
}
```

`ContextInputDerivation` é uma classe utilitária estática que caminha a árvore e acumula `field.input()` de todo `Comparison` e de toda `listField` de `AnyOf`.

- [ ] **Step 6: Rodar e ver passar**

```bash
./mvnw test -pl services/risk-engine -Dtest=PolicyCompilerTest+ContextInputDerivationTest
```

Esperado: PASS.

- [ ] **Step 7: Commit**

```bash
git add services/risk-engine/src
git commit -m "feat(riskpolicy): compilador de politica com as quatro travas do piso

Score nao negativo e a trava que sustenta o produto inteiro: ScoreAggregation ja
soma scores e reduz recomendacoes com strongest, entao regra custom so consegue
afrouxar a decisao por score negativo. Barrar isso na compilacao preserva a
monotonicidade que ja existia.

As outras tres: codigo em namespace CUSTOM_ que nao colide com familia do motor
nem com RegulatoryRiskRules; so campo do catalogo, com operador valido para o
tipo e campo de elemento so dentro do AnyOf da sua lista; teto de profundidade e
de nos.

requires() e derivado da arvore, nao declarado: a arvore nomeia os campos que le,
entao nao ha como declarar errado -- diferente do caminho de codigo, onde um teste
de bytecode precisa provar que o humano acertou.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Adaptador `RiskRule` e ligação com o motor

**Files:**
- Create: `risk/rule/interfaces/CustomRuleSource.java`
- Create: `risk/rule/interfaces/CustomRules.java`
- Create: `riskpolicy/service/CustomPolicyRiskRule.java`
- Modify: `risk/service/RiskScoringService.java`
- Modify: `risk/domain/model/RiskDecision.java`
- Test: `riskpolicy/service/CustomPolicyRiskRuleTest.java`
- Test: `risk/service/RiskScoringServiceTest.java` (ajuste do construtor)

**Interfaces:**
- Consumes: `PolicyRule` (Task 4), `ConditionEvaluator` (Task 3), `ContextInputDerivation` (Task 4).
- Produces: `CustomRules(Integer policyVersion, List<RiskRule> rules)` com a constante `CustomRules.NONE`; `CustomRuleSource.forContext(RiskContext)`; `RiskDecision.policyVersion()`.

- [ ] **Step 1: Escrever o teste do adaptador**

```java
package com.barrier.riskengine.riskpolicy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import com.barrier.riskengine.identity.domain.CompanyProfile;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomPolicyRiskRuleTest {

  private static final Instant AGORA = Instant.parse("2026-09-01T00:00:00Z");

  private CustomPolicyRiskRule regra(int score, RiskRecommendation rec) {
    Condition c =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.openingDate").orElseThrow(),
            Operator.WITHIN_LAST,
            Literal.duration(Period.ofMonths(6)));
    PolicyRule pr = new PolicyRule("CUSTOM_EMPRESA_NOVA", "Empresa nova", c, score, Severity.MEDIUM, rec);
    return new CustomPolicyRiskRule(pr, new ConditionEvaluator());
  }

  private RiskContext comAbertura(LocalDate quando) {
    CompanyProfile e = new CompanyProfile(quando, "6499-9/99", "d", List.of());
    return new RiskContext("a-1", "t-1", null, null, e, null, null, AGORA);
  }

  @Test
  void dispara_e_devolve_o_codigo_score_e_recomendacao_da_regra_do_parceiro() {
    RiskResult r = regra(150, RiskRecommendation.REVIEW).evaluate(comAbertura(LocalDate.of(2026, 7, 1)));

    assertThat(r.ruleCode()).isEqualTo("CUSTOM_EMPRESA_NOVA");
    assertThat(r.score()).isEqualTo(150);
    assertThat(r.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(r.triggered()).isTrue();
    assertThat(r.evidences()).isNotEmpty();
  }

  @Test
  void nao_dispara_devolve_nao_aplicavel() {
    RiskResult r = regra(150, RiskRecommendation.REVIEW).evaluate(comAbertura(LocalDate.of(2010, 1, 1)));

    assertThat(r.triggered()).isFalse();
    assertThat(r.score()).isZero();
    assertThat(r.recommendation()).isNull();
  }

  @Test
  void code_e_o_codigo_do_parceiro() {
    assertThat(regra(10, null).code()).isEqualTo("CUSTOM_EMPRESA_NOVA");
  }

  @Test
  void requires_vem_derivado_da_arvore() {
    assertThat(regra(10, null).requires()).containsExactly(ContextInput.COMPANY);
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
./mvnw test -pl services/risk-engine -Dtest=CustomPolicyRiskRuleTest
```

Esperado: erro de compilação.

- [ ] **Step 3: Criar a interface no módulo `risk` (inversão)**

`risk/rule/interfaces/CustomRules.java`:

```java
package com.barrier.riskengine.risk.rule.interfaces;

import java.util.List;

/**
 * Regras vindas da política do parceiro para uma avaliação, e a versão que as produziu.
 *
 * @param policyVersion versão da política ativa; {@code null} quando o tenant não tem nenhuma
 * @param rules regras já compiladas e prontas para entrar no motor
 */
public record CustomRules(Integer policyVersion, List<RiskRule> rules) {

  public static final CustomRules NONE = new CustomRules(null, List.of());

  public CustomRules {
    rules = List.copyOf(rules);
  }
}
```

`risk/rule/interfaces/CustomRuleSource.java`:

```java
package com.barrier.riskengine.risk.rule.interfaces;

import com.barrier.riskengine.risk.rule.context.RiskContext;

/**
 * Fonte de regras escritas pelo parceiro.
 *
 * <p><b>Declarada aqui, e não no módulo que a implementa.</b> O módulo {@code riskpolicy} precisa
 * de {@code RiskRule}, {@code RiskResult} e {@code RiskContext}, todos deste módulo; declarar a
 * interface lá fecharia o ciclo {@code risk → riskpolicy → risk}, que o ArchUnit
 * ({@code sem_ciclos_entre_modulos}) rejeita. Mesma inversão de
 * {@code AssuranceRecordedListener} e {@code AssessmentCompletedListener}.
 */
public interface CustomRuleSource {

  /** Regras da política ativa do tenant desta avaliação; {@link CustomRules#NONE} se não houver. */
  CustomRules forContext(RiskContext context);
}
```

- [ ] **Step 4: Implementar o adaptador**

`CustomPolicyRiskRule` implementa `RiskRule`, recebendo `PolicyRule` e `ConditionEvaluator` no construtor. Não é bean: é instanciado por avaliação pela fonte.

- `code()` devolve `rule.code()`.
- `requires()` devolve `ContextInputDerivation.of(rule.when())`, calculado uma vez no construtor.
- `evaluate(ctx)`: avalia; se casou, devolve `new RiskResult(code, score, severity, "política do parceiro: " + rule.name(), evidences, recommendation)`; senão `RiskResult.notApplicable(code)`.

- [ ] **Step 5: Ligar no motor**

Em `RiskDecision`, acrescentar o componente `Integer policyVersion` ao final.

Em `RiskScoringService`:

- Acrescentar `CustomRuleSource customRuleSource` ao construtor.
- No início de `evaluate(context)`, chamar `CustomRules custom = customRuleSource.forContext(context);`.
- Trocar `rules.stream()` por `Stream.concat(rules.stream(), custom.rules().stream())`.
- Passar `custom.policyVersion()` ao construir `RiskDecision`.
- Subir `ENGINE_VERSION` para `barrier-risk-rules/1.9.0` e acrescentar, acima das notas de versão existentes:

```java
  // 1.9.0: o motor ganhou uma segunda fonte de regras (CustomRuleSource): a política escrita pelo
  // parceiro entra no mesmo stream das regras de código e passa pela mesma ScoreAggregation.
  // Regra custom só endurece — a monotonicidade já existia (soma de score + reduce com
  // RiskRecommendation::strongest) e é preservada pela trava de score não negativo do
  // PolicyCompiler. Nenhuma regra nem peso de código mudou, mas uma decisão tomada nesta versão
  // pode conter fator que a anterior não conseguia produzir, e não subir mentiria na auditoria.
```

Acrescentar ao Javadoc da classe:

```java
 * <p>Regras custom entram no mesmo stream e portanto passam pelo mesmo {@code activeOrLogSuppressed}.
 * Isso é inofensivo e não precisa de caso especial: o registry é fail-open (regra sem linha fica
 * ativa, porque ele é kill switch e vigência, não allowlist) e código de regra custom nunca terá
 * linha lá. O kill switch de política custom é arquivar a versão ativa do tenant.
```

- [ ] **Step 6: Consertar `RiskScoringServiceTest`**

Passar `context -> CustomRules.NONE` como quarto argumento do construtor.

- [ ] **Step 7: Rodar a suíte do módulo**

```bash
./mvnw test -pl services/risk-engine
```

Esperado: PASS.

- [ ] **Step 8: Commit**

```bash
git add services/risk-engine/src
git commit -m "feat(risk): motor ganha fonte de regras do parceiro, por inversao

RiskScoringService ja recebia List<RiskRule> e nao sabia de onde vinham. Agora
recebe uma segunda fonte, resolvida por tenant: cada regra do parceiro vira um
RiskRule com codigo, score e evidencia proprios, cai no mesmo evaluated_json e
passa pela mesma ScoreAggregation. Tenant sem politica decide igual a antes.

A interface CustomRuleSource mora em risk, nao em riskpolicy: o modulo que a
implementa precisa de RiskRule/RiskContext, entao declara-la la fecharia o ciclo
risk -> riskpolicy -> risk. Mesma inversao do AssuranceRecordedListener.

ENGINE_VERSION 1.9.0: nenhuma regra de codigo mudou, mas decisao tomada nesta
versao pode conter fator que a anterior nao conseguia produzir.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Persistência e ciclo de vida

**Files:**
- Create: `services/risk-engine/src/main/resources/db/migration/V049__risk_policies.sql`
- Create: `riskpolicy/repository/RiskPolicyEntity.java`
- Create: `riskpolicy/repository/interfaces/RiskPolicyJpaRepository.java`
- Create: `riskpolicy/repository/interfaces/RiskPolicyRepository.java`
- Create: `riskpolicy/repository/RiskPolicyRepositoryImpl.java`
- Create: `riskpolicy/repository/PolicyRuleJson.java` (serialização da árvore)
- Create: `riskpolicy/service/RiskPolicyService.java`
- Test: `riskpolicy/repository/RiskPolicyRepositoryIntegrationTest.java`
- Test: `riskpolicy/service/RiskPolicyServiceTest.java`

**Interfaces:**
- Consumes: `RiskPolicy`, `PolicyRule`, `PolicyCompiler` (Task 4).
- Produces: `RiskPolicyRepository.create(RiskPolicy)`, `.findActive(String tenantId, PolicyDomain)`, `.findByTenantAndVersion(String, int)`, `.listByTenant(String)`, `.activate(UUID, String by, Instant)`, `.archive(UUID, Instant)`, `.nextVersion(String tenantId)`. `RiskPolicyService.createDraft(...)`, `.activate(...)`, `.archive(...)`.

- [ ] **Step 1: Escrever a migration**

`V049__risk_policies.sql`:

```sql
-- Política de risco escrita pelo parceiro (P1 do Risk Control Plane).
--
-- Uma VERSÃO ativada é imutável: editar gera versão nova. Isso não é preciosismo de modelagem, é
-- o que torna a decisão replayável. O motor já registra `engine_version` em risk_scores, mas
-- regra de código de uma versão antiga some do binário — a decisão de 1.4.0 é irreproduzível por
-- isso. Política guardada como artefato imutável não tem esse problema: ela pode ser reexecutada
-- para sempre. É o segundo eixo de versão, e o que permitiu reabrir a recusa de "regra como dado".
--
-- `rules_json` guarda a árvore de predicados inteira, no padrão de partners_json / hits_json /
-- evaluated_json. Não precisa ser consultável por dentro: é lida inteira na avaliação.
CREATE TABLE risk_policies (
    id              UUID         PRIMARY KEY,
    tenant_id       VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    -- ONBOARDING hoje. Existe com um valor só para que o domínio transacional entre como valor
    -- novo, e não como migração de conceito.
    domain          VARCHAR(30)  NOT NULL,
    -- Inteiro monotônico POR TENANT (não por domínio), para que /v1/policies/{version} identifique
    -- uma versão sem carregar o domínio na rota.
    version         INTEGER      NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    -- Versão do catálogo de campos contra a qual a política foi escrita. Campo do catálogo pode ser
    -- marcado obsoleto, nunca removido — mesma razão de migration Flyway ser imutável: política
    -- antiga precisa continuar interpretável.
    catalog_version INTEGER      NOT NULL,
    rules_json      JSONB        NOT NULL,
    created_by      VARCHAR(120) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    activated_by    VARCHAR(120),
    activated_at    TIMESTAMPTZ,
    archived_at     TIMESTAMPTZ,
    CONSTRAINT uq_risk_policies_version UNIQUE (tenant_id, version)
);

-- Uma ACTIVE por (tenant, domínio). O índice parcial é o que impede duas políticas ativas
-- decidirem ao mesmo tempo, inclusive sob corrida entre réplicas.
CREATE UNIQUE INDEX uq_risk_policies_uma_ativa
    ON risk_policies (tenant_id, domain)
    WHERE status = 'ACTIVE';

-- "Qual a política ativa deste tenant" é a consulta do caminho quente: roda uma vez por avaliação.
CREATE INDEX idx_risk_policies_ativa
    ON risk_policies (tenant_id, domain, status);

-- Segundo eixo de versão da decisão, ao lado de engine_version. Nulo para tenant sem política.
ALTER TABLE risk_scores ADD COLUMN policy_version INTEGER;
```

- [ ] **Step 2: Escrever o teste de integração que falha**

`RiskPolicyRepositoryIntegrationTest`, seguindo o padrão de Testcontainers já usado no módulo (copiar a classe base ou as anotações de um teste de integração vizinho, por exemplo o do `behavior`):

```java
@Test
void guarda_e_le_a_versao_ativa() { /* cria DRAFT, ativa, findActive devolve */ }

@Test
void so_uma_versao_ativa_por_tenant_e_dominio() {
  // ativar a segunda sem arquivar a primeira deve violar o índice parcial
}

@Test
void versao_e_monotonica_por_tenant() { /* nextVersion devolve 1, depois 2 */ }

@Test
void arvore_sobrevive_a_ida_e_volta_do_jsonb() {
  // criar política com AnyOf aninhado, reler, e comparar a Condition por igualdade de record
}

@Test
void tenant_sem_politica_devolve_vazio() { /* findActive -> Optional.empty */ }
```

- [ ] **Step 3: Rodar e ver falhar**

```bash
./mvnw test -pl services/risk-engine -Dtest=RiskPolicyRepositoryIntegrationTest
```

Esperado: falha. Confirmar que o Docker está rodando; sem ele o erro é `Can't get Docker image` e não é um resultado válido.

- [ ] **Step 4: Implementar serialização da árvore**

`PolicyRuleJson`: converte `List<PolicyRule>` para JSON e de volta, usando Jackson 3 (`tools.jackson.databind.ObjectMapper`). A árvore é selada, então usar `@JsonTypeInfo` com `@JsonSubTypes` nomeando cada nó (`and`, `or`, `not`, `cmp`, `anyOf`) e cada literal (`text`, `number`, `bool`, `date`, `duration`, `textSet`, `none`).

`PolicyField` **não** é serializado por inteiro: grava-se só o `id`, e a desserialização resolve pelo `FieldCatalog`. Gravar o extrator seria impossível e gravar tipo/exposição duplicaria a fonte de verdade. Campo que não resolve no catálogo na leitura é erro alto, não silencioso.

- [ ] **Step 5: Implementar entity, repositório e serviço**

`RiskPolicyEntity` com `@JdbcTypeCode(SqlTypes.JSON)` em `rulesJson`, seguindo `BehaviorEventEntity`.

`RiskPolicyRepository` — a interface **não tem** método que reescreva regra de versão ativada:

```java
/**
 * Versões de política. Não há {@code update} de conteúdo: versão ativada é imutável, e editar gera
 * versão nova. A ausência do método é a defesa, mesmo raciocínio de {@code BehaviorEventRepository}.
 * As únicas mutações são transição de estado.
 */
public interface RiskPolicyRepository {
  RiskPolicy create(RiskPolicy policy);
  Optional<RiskPolicy> findActive(String tenantId, PolicyDomain domain);
  Optional<RiskPolicy> findByTenantAndVersion(String tenantId, int version);
  List<RiskPolicy> listByTenant(String tenantId);
  int nextVersion(String tenantId);
  void activate(UUID id, String activatedBy, Instant when);
  void archive(UUID id, Instant when);
}
```

`RiskPolicyService` (`@Service`, `@Transactional`):

- `createDraft(tenantId, domain, rules, createdBy)`: compila (Task 4) antes de gravar; grava `catalogVersion = FieldCatalog.VERSION` e `version = nextVersion(tenantId)`.
- `activate(tenantId, version, activatedBy)`: arquiva a `ACTIVE` atual do mesmo domínio, se houver, e ativa a pedida **na mesma transação**. Recusa ativar versão que não seja `DRAFT`.
- `archive(tenantId, version)`.

- [ ] **Step 6: Rodar e ver passar**

```bash
./mvnw test -pl services/risk-engine -Dtest=RiskPolicyRepositoryIntegrationTest+RiskPolicyServiceTest
```

Esperado: PASS.

- [ ] **Step 7: Commit**

```bash
git add services/risk-engine/src
git commit -m "feat(riskpolicy): V049, versao de politica imutavel e ciclo de vida

Versao ativada e imutavel e editar gera versao nova -- e o que torna a decisao
replayavel. Regra de codigo de uma versao antiga some do binario (a decisao de
1.4.0 e irreproduzivel por isso); politica guardada como artefato nao tem esse
problema.

O repositorio nao expoe metodo que reescreva regra de versao ativada. So criar,
ativar e arquivar. Indice unico parcial garante uma ACTIVE por (tenant, dominio),
inclusive sob corrida entre replicas.

risk_scores.policy_version e o segundo eixo de versao da decisao, ao lado de
engine_version.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: `CustomRuleSourceImpl` e gravação da versão

**Files:**
- Create: `riskpolicy/service/CustomRuleSourceImpl.java`
- Modify: `risk/domain/model/RiskScore.java`
- Test: `riskpolicy/service/CustomRuleSourceImplTest.java`

**Interfaces:**
- Consumes: `RiskPolicyRepository` (Task 6), `CustomPolicyRiskRule` (Task 5), `ConditionEvaluator` (Task 3).
- Produces: o bean que satisfaz `CustomRuleSource`.

- [ ] **Step 1: Escrever o teste**

```java
@Test
void tenant_sem_politica_devolve_NONE() {
  assertThat(source.forContext(ctx("t-sem"))).isEqualTo(CustomRules.NONE);
}

@Test
void devolve_um_RiskRule_por_regra_da_politica_ativa() {
  // política ativa com 2 regras -> 2 RiskRule, com os códigos do parceiro
}

@Test
void devolve_a_versao_da_politica_que_produziu_as_regras() {
  assertThat(source.forContext(ctx("t-1")).policyVersion()).isEqualTo(3);
}

@Test
void politica_arquivada_nao_produz_regra() { }
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
./mvnw test -pl services/risk-engine -Dtest=CustomRuleSourceImplTest
```

- [ ] **Step 3: Implementar**

`CustomRuleSourceImpl` (`@Service`) lê `repository.findActive(context.tenantId(), PolicyDomain.ONBOARDING)` e mapeia cada `PolicyRule` para um `CustomPolicyRiskRule`.

**Sem cache, e o Javadoc tem que dizer por quê:**

```java
/**
 * <p><b>Sem cache local, de propósito.</b> Guardar a política ativa num mapa de instância seria
 * estado do cluster na memória de um pod: ativação feita numa réplica não invalidaria o cache das
 * outras, e elas seguiriam decidindo com a política anterior. É exatamente o modo de falha que este
 * projeto já pagou três vezes ({@code WatchlistImportStatus}, o dedup do {@code AlertEvaluator} e
 * os tópicos do {@code KafkaTopicsConfig}), e nas três havia comentário explicando por que estava
 * certo. A consulta é uma leitura indexada por (tenant, domínio, status), uma vez por avaliação;
 * se virar gargalo medido, a saída é cache com invalidação compartilhada, não mapa local.
 */
```

- [ ] **Step 4: Persistir a versão**

Em `RiskScore.from(context, decision)`, passar `decision.policyVersion()` para o novo campo, e mapear a coluna `policy_version` na entity correspondente.

- [ ] **Step 5: Rodar e ver passar**

```bash
./mvnw test -pl services/risk-engine -Dtest=CustomRuleSourceImplTest
```

- [ ] **Step 6: Commit**

```bash
git add services/risk-engine/src
git commit -m "feat(riskpolicy): fonte de regras le a versao ativa por avaliacao

Sem cache local de proposito: politica ativa num mapa de instancia seria estado
de cluster na memoria de um pod, e ativacao feita numa replica nao invalidaria as
outras. E o modo de falha que o projeto ja pagou tres vezes na frente de escala
horizontal, sempre com comentario explicando por que estava certo.

risk_scores.policy_version passa a ser gravada.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: API REST e contrato

**Files:**
- Create: `riskpolicy/controller/RiskPolicyController.java`
- Create: `riskpolicy/controller/PolicyFieldController.java`
- Create: `riskpolicy/controller/dto/` (requests e responses)
- Modify: a configuração de grupos do springdoc (o grupo `parceiro`)
- Test: `riskpolicy/controller/RiskPolicyControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `RiskPolicyService` (Task 6), `FieldCatalog` (Task 2), `AuthenticatedTenant`.
- Produces: as seis rotas da §9 do spec.

> **Nota de correção ao spec:** o spec diz que `ApiRoutes` precisa classificar as rotas novas como parceiro. **Não precisa.** `ApiRoutes` é uma *denylist*: tudo sob `/v1/` é tenant-scoped exceto o que casa o padrão administrativo. `/v1/policies` e `/v1/policy-fields` já nascem como rota de parceiro. Verificar isso com um teste em vez de assumir, e não editar o padrão `ADMIN`.

- [ ] **Step 1: Escrever o teste de integração**

```java
@Test
void cria_rascunho_ativa_e_a_regra_passa_a_pontuar() {
  // POST /v1/policies com uma regra CUSTOM_EMPRESA_NOVA
  // POST /v1/policies/{v}/activate
  // POST /v1/assessments de uma PJ recém-aberta
  // GET /v1/assessments/{id} traz o fator CUSTOM_EMPRESA_NOVA
}

@Test
void politica_invalida_responde_400_citando_campo_e_operador() {
  // score negativo -> 400, corpo cita "score" e o código da regra
}

@Test
void nao_enxerga_politica_de_outro_tenant() {
  // tenant B pede GET /v1/policies/{versão do tenant A} -> 404
}

@Test
void rota_de_politica_e_de_parceiro_e_nao_administrativa() {
  assertThat(ApiRoutes.isTenantScoped("/v1/policies")).isTrue();
  assertThat(ApiRoutes.isAdmin("/v1/policies")).isFalse();
  assertThat(ApiRoutes.isTenantScoped("/v1/policy-fields")).isTrue();
}
```

O último teste vive em `web` (mesmo pacote de `ApiRoutes`, que é package-private).

- [ ] **Step 2: Rodar e ver falhar**

```bash
./mvnw test -pl services/risk-engine -Dtest=RiskPolicyControllerIntegrationTest
```

- [ ] **Step 3: Implementar DTOs e controllers**

O tenant vem de `AuthenticatedTenant`, injetado pelo `TenantArgumentResolver`, **nunca do path**. Seguir `BehaviorEventController` como modelo.

Rotas:

| Método | Rota | Resposta |
|---|---|---|
| POST | `/v1/policies` | 201 com a versão criada |
| GET | `/v1/policies` | 200, lista |
| GET | `/v1/policies/{version}` | 200 ou 404 |
| POST | `/v1/policies/{version}/activate` | 200 |
| POST | `/v1/policies/{version}/archive` | 200 |
| GET | `/v1/policy-fields` | 200, catálogo com versão |

`PolicyCompilationException` vira 400 por `@ExceptionHandler`, com corpo citando o código da regra, o campo, o operador e a trava violada. Versão inexistente ou de outro tenant vira 404, nunca 403: 403 confirmaria que a versão existe.

- [ ] **Step 4: Declarar no grupo `parceiro` do OpenAPI**

Acrescentar os caminhos ao grupo `parceiro`. **Não** acrescentar ao grupo `admin`. Rodar `OpenApiCoverageIntegrationTest`, que quebra o build se rota de negócio nascer sem contrato.

- [ ] **Step 5: Rodar e ver passar**

```bash
./mvnw test -pl services/risk-engine -Dtest=RiskPolicyControllerIntegrationTest+OpenApiCoverageIntegrationTest+ApiRouteCoverageTest
```

- [ ] **Step 6: Commit**

```bash
git add services/risk-engine/src
git commit -m "feat(riskpolicy): /v1/policies e /v1/policy-fields como rota de parceiro

O invariante aditivo e o que paga o self-service: regra que so endurece pode ser
escrita pelo proprio parceiro sem risco regulatorio. /v1/tenants/{id}/risk-config
continua administrativo porque calibra parametro e pode afrouxar. O criterio fica
escrito: quem so endurece e self-service, quem calibra segue admin.

ApiRoutes nao precisou mudar -- e denylist, entao rota nova sob /v1/ ja nasce
tenant-scoped. Confirmado por teste em vez de assumido.

Erro de compilacao de politica responde 400 citando campo, operador e trava. Versao
de outro tenant responde 404 e nunca 403, que confirmaria a existencia.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: O invariante aditivo e as amarras de arquitetura

Esta tarefa é a entrega de verdade: é o que impede o piso de erodir por acidente futuro.

**Files:**
- Test: `riskpolicy/InvarianteAditivoTest.java`
- Modify: `architecture/LayeredArchitectureTest.java`

- [ ] **Step 1: Escrever o teste do invariante**

```java
package com.barrier.riskengine.riskpolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A promessa do produto, verificada: para qualquer contexto e qualquer política compilável, a
 * decisão COM política nunca é mais fraca que a decisão SEM ela.
 *
 * <p>Não é teste de exemplo: é a propriedade que sustenta a decisão de abrir escrita de regra ao
 * parceiro. Se ela cair, o piso regulatório caiu junto.
 */
class InvarianteAditivoTest {

  @Test
  void politica_nunca_enfraquece_a_decisao() {
    // Para cada combinação de: contexto (PF limpa, PF com sanção, PJ sem QSA, PJ com sócio
    // estrangeiro, identidade indisponível) x política (vazia, só pontua, força REVIEW,
    // força REJECT, várias regras):
    //   RiskDecision sem = motorSemPolitica.evaluate(ctx);
    //   RiskDecision com = motorComPolitica.evaluate(ctx);
    //   assertThat(com.totalScore()).isGreaterThanOrEqualTo(sem.totalScore());
    //   assertThat(com.recommendation().strongest(sem.recommendation()))
    //       .isEqualTo(com.recommendation());
  }

  @Test
  void nenhuma_regra_custom_apaga_fator_do_motor() {
    // todo ruleCode presente em `sem.evaluated()` continua presente em `com.evaluated()`
  }

  @Test
  void o_compilador_e_a_unica_porta_e_ela_recusa_score_negativo() {
    // tentar construir a política afrouxadora pelo caminho público (o serviço) falha
  }
}
```

- [ ] **Step 2: Rodar e ver falhar, depois passar**

```bash
./mvnw test -pl services/risk-engine -Dtest=InvarianteAditivoTest
```

Se passar de primeira, **verificar por mutação**: trocar temporariamente a trava de score para permitir negativo e confirmar que o teste quebra. Teste de invariante que passa por acidente não vale nada. Reverter a mutação em seguida.

- [ ] **Step 3: Acrescentar as regras de ArchUnit**

Em `LayeredArchitectureTest`:

```java
/**
 * Regra escrita por parceiro não sai para a rede. O módulo não depende de nenhum pacote
 * {@code client}, então não tem como chamar o que não enxerga — mesma garantia estrutural de
 * {@code replay_nao_alcanca_integracao_externa}.
 */
@ArchTest
static final ArchRule politica_custom_nao_alcanca_integracao_externa =
    noClasses()
        .that()
        .resideInAPackage("com.barrier.riskengine.riskpolicy..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..client..");
```

`sem_ciclos_entre_modulos` já cobre a inversão de `CustomRuleSource` sem alteração.

- [ ] **Step 4: Rodar a suíte inteira**

```bash
./mvnw test -pl services/risk-engine
```

- [ ] **Step 5: Commit**

```bash
git add services/risk-engine/src
git commit -m "test(riskpolicy): o invariante aditivo virou teste, provado por mutacao

Para qualquer contexto e qualquer politica compilavel, a decisao com politica
nunca e mais fraca que a decisao sem ela, nem em score nem em recomendacao, e
nenhum fator do motor desaparece. Nao e teste de exemplo: e a propriedade que
sustenta abrir escrita de regra ao parceiro.

ArchUnit: riskpolicy nao depende de pacote client -- regra de parceiro nao sai
para a rede, e a garantia e estrutural, nao disciplinar.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: Replay ponta a ponta e documentação

**Files:**
- Modify: `replay/` — o dossiê passa a reportar `policyVersion`
- Test: `replay/DecisionReplayIntegrationTest.java` (casos novos)
- Modify: `CLAUDE.md`
- Modify: `docs/product/backlog.md`
- Modify: `docs/architecture/event-catalog.md` se algum payload mudar (não deve mudar)

- [ ] **Step 1: Escrever os testes de replay**

```java
@Test
void as_decided_permanece_integro_com_fator_custom() {
  // decisão tomada com política ativa; AS_DECIDED recalcula e confere
}

@Test
void dossie_reporta_a_versao_da_politica_que_decidiu() { }

@Test
void regra_custom_que_le_company_em_pj_vira_nao_replayavel() {
  // CompanyProfile é transiente; requires() derivado marca NOT_REPLAYABLE
}

@Test
void current_engine_separa_mudanca_de_motor_de_mudanca_de_politica() {
  // decidir com política v1, ativar v2, replayar: o diff atribui a diferença à política
}
```

- [ ] **Step 2: Implementar o que faltar no dossiê**

Acrescentar `policyVersion` (como decidido e hoje) à resposta do replay. Não acrescentar documento nem nome: a resposta continua carregando só código de regra, pontuação e versões.

- [ ] **Step 3: Rodar a suíte inteira dos dois serviços**

```bash
./mvnw test
```

Esperado: verde. Anotar a contagem real de testes para atualizar o `CLAUDE.md`.

- [ ] **Step 4: Atualizar a documentação**

Em `CLAUDE.md`, acrescentar uma seção no padrão das existentes cobrindo: o catálogo como interface, o invariante aditivo e por que ele reabre a recusa registrada, a inversão de `CustomRuleSource`, a ausência deliberada de cache, `ENGINE_VERSION` 1.9.0 e a nova contagem de testes com a data.

Em `docs/product/backlog.md`, registrar P1 como concluído e P2 (shadow/backtest) como o próximo da sequência, com a razão: até P2 existir, o parceiro ativa política no escuro.

Em `docs/implementation/archive/README.md`, anotar ao lado da recusa "regra customizável pelo parceiro" que ela foi **reaberta com argumento** e onde está o argumento. Não apagar a recusa: o racional dela continua válido para regra subtrativa, e é o que explica por que a solução tem a forma que tem.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(replay): dossie separa mudanca de motor de mudanca de politica

Dois eixos de versao, cada um 'como decidido' e 'hoje'. Politica diferente e fonte
legitima de diferenca, e o ponto e que ela fica atribuivel em vez de aparecer como
o motor tendo mudado de opiniao.

Documenta a frente no CLAUDE.md e no backlog, e anota no arquivo que a recusa de
'regra customizavel' foi reaberta com argumento -- sem apaga-la, porque o racional
dela segue valendo para regra subtrativa e e o que explica a forma da solucao.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Autorrevisão do plano

**Cobertura do spec, seção a seção:**

| Seção do spec | Tarefa |
|---|---|
| §5.1 catálogo de campos | Task 2 |
| §5.2 árvore de predicados | Task 3 |
| §5.3 regra do parceiro | Task 4 |
| §5.4 avaliação e integração | Task 5 |
| §5.5 quatro travas | Task 4 |
| §5.6 instante de referência | Task 1 |
| §6 ciclo de vida | Task 6 |
| §7 persistência (V049) | Task 6 |
| §8 trilha, replay, autoria | Tasks 7 e 10 |
| §9 API | Task 8 |
| §10 módulo e inversão | Tasks 5 e 9 |
| §11 testes | distribuído, com Task 9 concentrando o invariante |

**Correção ao spec encontrada durante o planejamento:** a §9 afirma que `ApiRoutes` precisa classificar as rotas novas. Não precisa, porque `ApiRoutes` é denylist. Registrado na Task 8 e a ser corrigido no spec.

**Consistência de tipos:** `CustomRules.policyVersion` é `Integer` (nulo quando não há política), consistente com `risk_scores.policy_version INTEGER` e com `RiskDecision.policyVersion`. `PolicyField.resolve(Object)` aceita `RiskContext` ou elemento de lista, consistente entre Tasks 2, 3 e 4. `FieldCatalog.VERSION` é `int`, gravado em `catalog_version INTEGER`.
