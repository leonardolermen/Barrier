package com.barrier.riskengine.riskpolicy.config;

import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publica o catálogo de campos de política como bean, para {@code PolicyCompiler} (e qualquer
 * outro consumidor do módulo) receber por injeção de construtor em vez de referenciar {@code
 * FieldCatalog.V1} direto.
 *
 * <p>{@link FieldCatalog} tem construtor privado e nenhuma anotação Spring, de propósito: é um
 * catálogo estático versionado, não um objeto com ciclo de vida gerenciado. Sem este bean, o
 * {@code RiskEngineApplication} — que escaneia {@code com.barrier} inteiro e instancia {@code
 * @Component} ansiosamente — não conseguia montar o grafo de dependências de {@code
 * PolicyCompiler}, e o contexto inteiro falhava na subida (achado da revisão da Task 4; ver
 * {@code FieldCatalogConfigIntegrationTest}).
 */
@Configuration
class FieldCatalogConfig {

  @Bean
  FieldCatalog fieldCatalog() {
    return FieldCatalog.V1;
  }
}
