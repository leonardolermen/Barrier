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
          .withOptionalLayers(true)
          .layer("Controller")
          .definedBy("..controller..")
          .layer("Service")
          .definedBy("..service..")
          .layer("Repository")
          .definedBy("..repository..")
          .whereLayer("Controller")
          .mayNotBeAccessedByAnyLayer()
          // Service pode ser acessado por Controller e por outro Service (orquestração
          // entre módulos: ex. AssessmentProcessor -> IdentityService).
          .whereLayer("Service")
          .mayOnlyBeAccessedByLayers("Controller", "Service")
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

  /**
   * Regras de risco/screening regulatórias fixas (bandas de score, identidade, PEP, sanção) não
   * podem virar configuráveis por tenant por engano — só regras de apetite de risco (ex.:
   * {@code NewCompanyRiskRule}, {@code SensitiveCnaeRiskRule}) podem depender do serviço de
   * config por tenant.
   */
  @ArchTest
  static final ArchRule regras_fixas_nao_dependem_de_config_por_tenant =
      com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
          .that()
          .haveSimpleName("IdentityRiskRule")
          .or()
          .haveSimpleName("PepRiskRule")
          .or()
          .haveSimpleName("SanctionRiskRule")
          .or()
          .haveSimpleName("PepMatchRule")
          .or()
          .haveSimpleName("SanctionMatchRule")
          .or()
          .haveSimpleName("RiskScoringService")
          .should()
          .dependOnClassesThat()
          .haveSimpleName("TenantRiskConfigService");
}
