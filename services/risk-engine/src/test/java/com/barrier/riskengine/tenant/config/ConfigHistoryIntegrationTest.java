package com.barrier.riskengine.tenant.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.risk.registry.repository.interfaces.RiskRuleRegistryRepository;
import com.barrier.riskengine.tenant.config.repository.TenantRiskConfigRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Prova, contra Postgres real, que alterar configuração de risco deixa linha do tempo.
 *
 * <p>Precisa de banco de verdade porque o que está sendo testado é justamente o SQL: o histórico é
 * escrito por {@code INSERT} explícito, na mesma transação da alteração. Um mock do repositório
 * confirmaria que o método foi chamado e não que a linha existe — que é a única coisa que o
 * regulador vai olhar.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class ConfigHistoryIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired TenantRiskConfigRepository tenantConfig;
  @Autowired RiskRuleRegistryRepository registry;
  @Autowired JdbcTemplate jdbc;

  /**
   * O valor de hoje responde "quanto vale agora", nunca "quanto valia em março". Com o override
   * sobrescrito no lugar, uma decisão antiga ficava sem como ser explicada.
   */
  @Test
  void cadaAlteracaoDeOverrideViraUmaLinhaDoHistorico() {
    tenantConfig.upsert("default", "NEW_COMPANY", "months", "6", "ana");
    tenantConfig.upsert("default", "NEW_COMPANY", "months", "12", "bruno");

    List<Map<String, Object>> historico =
        jdbc.queryForList(
            "SELECT param_value, updated_by FROM tenant_risk_config_history"
                + " WHERE tenant_id = ? AND rule_code = ? AND param_key = ?"
                + " ORDER BY changed_at, param_value",
            "default",
            "NEW_COMPANY",
            "months");

    assertThat(historico).hasSize(2);
    assertThat(historico.get(0)).containsEntry("param_value", "6").containsEntry("updated_by", "ana");
    assertThat(historico.get(1))
        .containsEntry("param_value", "12")
        .containsEntry("updated_by", "bruno");
    // A tabela viva continua com o valor corrente: o histórico acompanha, não substitui.
    assertThat(tenantConfig.find("default", "NEW_COMPANY", "months").orElseThrow().paramValue())
        .isEqualTo("12");
  }

  /**
   * Kill switch é o caso que mais precisa de trilha: uma regra desligada por uma semana e religada
   * não deixava vestígio nenhum da semana em que não rodou.
   */
  @Test
  void desligarEReligarUmaRegraDeixaOsDoisEstadosNoHistorico() {
    registry.upsert("NEW_COMPANY", "Empresa nova", "ALERT", false, null, null, "ana");
    registry.upsert("NEW_COMPANY", "Empresa nova", "ALERT", true, null, null, "bruno");

    List<Map<String, Object>> historico =
        jdbc.queryForList(
            "SELECT enabled, updated_by FROM risk_rule_registry_history"
                + " WHERE rule_code = ? ORDER BY changed_at, enabled",
            "NEW_COMPANY");

    assertThat(historico).hasSize(2);
    assertThat(historico.get(0)).containsEntry("enabled", false).containsEntry("updated_by", "ana");
    assertThat(historico.get(1)).containsEntry("enabled", true).containsEntry("updated_by", "bruno");
  }
}
