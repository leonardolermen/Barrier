package com.barrier.riskengine.riskpolicy.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.service.PolicyCompiler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Sobe o contexto real da aplicação e confirma que {@link PolicyCompiler} é construído.
 *
 * <p><b>Por que este teste existe, e não só o unitário.</b> {@code PolicyCompilerTest} chama
 * {@code new PolicyCompiler(FieldCatalog.V1)} direto, sem passar pelo container — prova que a
 * classe funciona, não que ela pode ser <b>construída</b> no ambiente real. {@link
 * PolicyCompiler} é {@code @Component} com {@link FieldCatalog} injetado por construtor, mas
 * {@code FieldCatalog} tem construtor privado e nenhuma anotação Spring — sem um bean publicando
 * {@code FieldCatalog.V1}, o {@code RiskEngineApplication} (que escaneia {@code com.barrier}
 * inteiro e instancia {@code @Component} ansiosamente) não consegue montar o grafo de
 * dependências, e o contexto inteiro falha na subida. Foi exatamente esse o defeito: passava aqui
 * como teste unitário e quebrava 30 classes de teste de integração que sobem o contexto de
 * verdade.
 *
 * <p>Contexto completo (não um recorte com {@code classes = {...}}) de propósito — é o ambiente
 * real que precisa ser provado, e um contexto reduzido só provaria que os dois beans se encaixam
 * entre si, não que {@link FieldCatalogConfig} sobrevive à inicialização junto de todo o resto.
 */
@SpringBootTest
@Testcontainers
class FieldCatalogConfigIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired private PolicyCompiler policyCompiler;

  @Test
  void contexto_sobe_e_policy_compiler_e_injetado() {
    assertThat(policyCompiler).isNotNull();
  }
}
