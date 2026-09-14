package com.barrier.riskengine.riskpolicy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import com.barrier.riskengine.riskpolicy.domain.PolicyStatus;
import com.barrier.riskengine.riskpolicy.domain.RiskPolicy;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import java.util.List;
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

  /**
   * O caso que só aparece contra banco real: {@code activate} arquiva a política ativa atual e
   * ativa a nova <b>na mesma transação</b>, e o índice único parcial {@code
   * uq_risk_policies_uma_ativa} (uma {@code ACTIVE} por tenant/domínio) não é {@code DEFERRABLE}
   * — índice parcial não pode ser, no Postgres. {@code RiskPolicyServiceTest} (unitário, com
   * repositório fake em memória) prova que a <b>ordem lógica</b> está certa, mas o fake não tem
   * constraint nenhuma, então nunca pegaria uma violação de ordem de <b>flush</b>. Esta é a
   * primeira ativação de uma <b>segunda</b> versão contra o banco de verdade neste projeto.
   */
  @Test
  void ativar_segunda_versao_arquiva_a_primeira_sem_violar_o_indice_parcial() {
    String tenantId = "default";
    PolicyRule regra1 = regraSempreVerdadeira("CUSTOM_V1");
    PolicyRule regra2 = regraSempreVerdadeira("CUSTOM_V2");

    RiskPolicy v1 =
        riskPolicyService.createDraft(
            tenantId, PolicyDomain.ONBOARDING, List.of(regra1), "teste-integracao@barrier");
    riskPolicyService.activate(tenantId, v1.version(), "teste-integracao@barrier");

    RiskPolicy v2 =
        riskPolicyService.createDraft(
            tenantId, PolicyDomain.ONBOARDING, List.of(regra2), "teste-integracao@barrier");

    assertThatCode(() -> riskPolicyService.activate(tenantId, v2.version(), "teste-integracao@barrier"))
        .as(
            "ativar a segunda versão não pode violar uq_risk_policies_uma_ativa -- é o ciclo de "
                + "vida normal a partir da segunda ativação, não um caso de borda")
        .doesNotThrowAnyException();

    assertThat(riskPolicyService.get(tenantId, v1.version()).status()).isEqualTo(PolicyStatus.ARCHIVED);
    assertThat(riskPolicyService.get(tenantId, v2.version()).status()).isEqualTo(PolicyStatus.ACTIVE);
  }

  private static PolicyRule regraSempreVerdadeira(String codigo) {
    Condition sempre =
        new Condition.Comparison(
            FieldCatalog.V1.find("identity.status").orElseThrow(), Operator.IS_NOT_NULL, Literal.none());
    return new PolicyRule(codigo, codigo, sempre, 10, Severity.LOW, null);
  }
}
