package com.barrier.riskengine.riskpolicy.service;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Sobe o contexto real da aplicação e confirma que {@link RiskPolicyService} é construído.
 *
 * <p>Mesmo raciocínio de {@code FieldCatalogConfigIntegrationTest}: um teste unitário que faz
 * {@code new RiskPolicyService(repositorioFalso, compilador)} prova que a classe funciona, não que
 * ela pode ser <b>montada</b> pelo {@code RiskEngineApplication} de verdade. Esta task acrescenta
 * repositório, entidade e serviço novos na cadeia de injeção
 * ({@code RiskPolicyService -> RiskPolicyRepository -> RiskPolicyRepositoryImpl -> (JPA +
 * PolicyRuleJson) -> FieldCatalog}) — exatamente a classe de defeito que a Task 4 cometeu (bean
 * cuja dependência não existia, sem nada pegando por 25 minutos) e que este teste existe para
 * pegar imediatamente, sem depender de outro teste de integração ter tropeçado nele por acidente.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class RiskPolicyServiceIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired private RiskPolicyService riskPolicyService;

  @Test
  void contexto_sobe_e_risk_policy_service_e_injetado() {
    assertThat(riskPolicyService).isNotNull();
  }
}
