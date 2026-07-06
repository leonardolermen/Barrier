package com.barrier.riskengine.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

/**
 * Regras de arquitetura em camadas do Barrier (ver docs/implementation/coding-standards.md).
 *
 * <p>Cada módulo interno segue {@code controller -> service -> repository}, com integrações
 * externas atrás de interface no pacote {@code client}. Na Fase 0 os pacotes ainda estão
 * vazios; as regras passam vacuamente e começam a valer conforme o código é escrito.
 */
@AnalyzeClasses(
    packages = "com.barrier.riskengine",
    importOptions = ImportOption.DoNotIncludeTests.class)
class LayeredArchitectureTest {

  @ArchTest
  static final ArchRule camadas_respeitam_a_direcao =
      Architectures.layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Controller")
          .definedBy("..controller..")
          .layer("Service")
          .definedBy("..service..")
          .layer("Repository")
          .definedBy("..repository..")
          .whereLayer("Controller")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Service")
          .mayOnlyBeAccessedByLayers("Controller")
          .whereLayer("Repository")
          .mayOnlyBeAccessedByLayers("Service");

  @ArchTest
  static final ArchRule controller_nao_acessa_repository =
      com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
          .that()
          .resideInAPackage("..controller..")
          .should()
          .accessClassesThat()
          .resideInAPackage("..repository..");

  @ArchTest
  static final ArchRule sem_ciclos_entre_modulos =
      SlicesRuleDefinition.slices()
          .matching("com.barrier.riskengine.(*)..")
          .should()
          .beFreeOfCycles();
}
