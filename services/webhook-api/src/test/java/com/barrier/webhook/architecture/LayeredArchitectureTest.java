package com.barrier.webhook.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;

/** Regras de arquitetura em camadas da Webhook API (ver docs/implementation/coding-standards.md). */
@AnalyzeClasses(
    packages = "com.barrier.webhook",
    importOptions = ImportOption.DoNotIncludeTests.class)
// público para poder ser referenciado por @SelectClasses na AllTestsSuite
public class LayeredArchitectureTest {

  /**
   * Guarda contra o modo de falha silencioso: quando o ASM empacotado no ArchUnit não lê o bytecode
   * do JDK em uso, o import falha classe a classe num WARN e a regra abaixo passa **vacuamente**
   * sobre zero classes — controle verde sem verificar nada.
   *
   * <p>O teto era 20; a máquina de entrega (a maior parte das classes do módulo) saiu para
   * {@code com.barrier:webhook-delivery} (Task 12 da extração), e o que fica aqui — Kafka,
   * reconciliação, API administrativa — são ~14 arquivos. 10 ainda é folga suficiente para
   * continuar pegando o caso de zero/poucas classes do defeito do ASM, sem falsear a asserção
   * depois da extração.
   */
  @ArchTest
  static void o_import_enxerga_as_classes_do_modulo(JavaClasses classes) {
    assertThat(classes.size())
        .as("ArchUnit importou classes de menos — a regra abaixo passaria vacuamente")
        .isGreaterThan(10);
  }

  @ArchTest
  static final ArchRule camadas_respeitam_a_direcao =
      Architectures.layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .withOptionalLayers(true)
          .layer("Controller")
          .definedBy("..controller..")
          .layer("Service")
          .definedBy("..service..")
          .layer("Repository")
          .definedBy("..repository..")
          .whereLayer("Controller")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Service")
          .mayOnlyBeAccessedByLayers("Controller", "Service")
          .whereLayer("Repository")
          .mayOnlyBeAccessedByLayers("Service");
}
