package com.barrier.webhook.architecture;

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
