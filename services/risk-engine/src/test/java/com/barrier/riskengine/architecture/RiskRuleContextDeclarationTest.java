package com.barrier.riskengine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Cada {@code RiskRule} declara em {@code requires()} <b>exatamente</b> os campos do
 * {@link RiskContext} que ela lê.
 *
 * <p><b>Por que este teste existe.</b> O replay de decisão usa essa declaração para marcar uma regra
 * como {@code NOT_REPLAYABLE} quando um insumo dela não foi reconstruído. Se a declaração ficar
 * menor que o uso real, a regra roda sobre insumo ausente, devolve "não disparou", e o replay
 * reporta isso como <i>o motor de hoje decidiria diferente</i> — atribuindo a uma mudança de regra o
 * que é falta de dado. O compilador obriga a declarar (não há default em {@code requires()}); é este
 * teste que obriga a declarar <b>certo</b>.
 *
 * <p>Compara duas fontes independentes do mesmo fato, ambas lidas do bytecode: as chamadas a
 * acessores de {@code RiskContext} feitas pela classe, e as constantes de {@link ContextInput}
 * referenciadas dentro de {@code requires()}. Nenhuma lista escrita à mão — regra nova entra sozinha.
 */
class RiskRuleContextDeclarationTest {

  /** Acessor de {@code RiskContext} → o insumo correspondente. Campos fora daqui não são insumo. */
  private static final Map<String, ContextInput> ACESSORES =
      Map.of(
          "identity", ContextInput.IDENTITY,
          "screening", ContextInput.SCREENING,
          "company", ContextInput.COMPANY,
          "profile", ContextInput.PROFILE,
          "assurance", ContextInput.ASSURANCE);

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.barrier.riskengine.risk.rule");

  private static Set<JavaClass> regras() {
    return CLASSES.stream()
        .filter(c -> c.isAssignableTo(RiskRule.class))
        .filter(c -> !c.isInterface() && !c.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT))
        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
  }

  /**
   * Guarda antivácuo, no padrão de {@code LayeredArchitectureTest}: se o importador não enxergar as
   * classes, a comparação abaixo passa sobre conjunto vazio e o controle fica verde sem verificar
   * nada.
   */
  @Test
  void o_import_enxerga_as_regras() {
    assertThat(regras())
        .as("ArchUnit não importou as regras de risco — a verificação abaixo passaria vacuamente")
        .hasSizeGreaterThanOrEqualTo(10);
  }

  @Test
  void toda_regra_declara_exatamente_os_insumos_que_usa() {
    Map<String, String> divergencias = new LinkedHashMap<>();

    for (JavaClass regra : regras()) {
      Set<ContextInput> usados = usados(regra);
      Set<ContextInput> declarados = declarados(regra);
      if (!usados.equals(declarados)) {
        divergencias.put(
            regra.getSimpleName(),
            "usa " + new TreeSet<>(nomes(usados)) + " mas declara " + new TreeSet<>(nomes(declarados)));
      }
    }

    assertThat(divergencias)
        .as(
            "requires() precisa espelhar o que a regra lê do RiskContext — declaração a menos faz o "
                + "replay reportar falta de dado como mudança de motor")
        .isEmpty();
  }

  /** Chamadas a acessores de {@code RiskContext} feitas em qualquer código da classe. */
  private static Set<ContextInput> usados(JavaClass regra) {
    return regra.getMethodCallsFromSelf().stream()
        .filter(call -> call.getTargetOwner().isEquivalentTo(RiskContext.class))
        .map(call -> ACESSORES.get(call.getName()))
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
  }

  /**
   * Constantes de {@link ContextInput} referenciadas <b>de dentro de {@code requires()}</b>.
   *
   * <p>{@code Set.of(ContextInput.COMPANY)} compila para um acesso ao campo estático — e
   * {@code Set.of()} não produz acesso nenhum, que é como uma declaração legitimamente vazia se
   * distingue de uma esquecida (esta última não compila, porque o método não tem default).
   */
  private static Set<ContextInput> declarados(JavaClass regra) {
    return regra.getFieldAccessesFromSelf().stream()
        .filter(access -> access.getOrigin().getName().equals("requires"))
        .filter(access -> access.getTargetOwner().isEquivalentTo(ContextInput.class))
        .map(access -> ContextInput.valueOf(access.getName()))
        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
  }

  private static Set<String> nomes(Set<ContextInput> inputs) {
    return inputs.stream().map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
  }
}
