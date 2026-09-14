package com.barrier.riskengine.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.riskpolicy.service.CustomRuleSourceImpl;
import com.barrier.riskengine.risk.rule.interfaces.CustomRuleSource;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Sobe o contexto real da aplicação e confirma que {@link RiskScoringService} é construído com o
 * único bean {@link CustomRuleSource} — {@link CustomRuleSourceImpl}, Task 7 — de fato injetado.
 *
 * <p><b>Por que este teste existe, e não só o unitário.</b> {@code RiskScoringServiceTest} chama
 * {@code new RiskScoringService(...)} direto, sem passar pelo container — prova que a classe
 * funciona, não que o grafo de dependências real se fecha. Antes da Task 7, este arquivo provava
 * o estado simétrico (zero beans, {@code Optional} resolvendo para vazio — ver {@code
 * CustomRuleSourceImplTest} e {@code RiskScoringServiceTest} para essa mesma garantia no nível
 * unitário, que não depende do container e continua valendo). Com a Task 7, o estado alcançável
 * pela aplicação real passou a ser sempre "exatamente um bean", e é isso que este teste passou a
 * provar — as duas pontas do {@code Optional<CustomRuleSource>} seguem cobertas, cada uma no
 * nível que faz sentido provar: o container prova o que existe hoje, o unitário prova que a
 * ausência não quebraria a subida se ela pudesse ocorrer.
 *
 * <p>Contexto completo (não um recorte com {@code classes = {...}}) de propósito, no mesmo
 * padrão de {@code FieldCatalogConfigIntegrationTest} — é o ambiente real que precisa ser
 * provado.
 */
@SpringBootTest
@Testcontainers
class RiskScoringServiceContextIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired private RiskScoringService riskScoringService;
  @Autowired private ApplicationContext applicationContext;

  @Test
  void contexto_sobe_e_risk_scoring_service_e_injetado() {
    assertThat(riskScoringService).isNotNull();
  }

  /**
   * Prova por execução, não por leitura: pega o valor real do campo privado {@code
   * customRuleSource} da instância que o Spring construiu, em vez de inferir a partir da
   * contagem de beans que a resolução do {@code Optional} "deveria" funcionar.
   */
  @Test
  void risk_scoring_service_recebe_o_bean_de_custom_rule_source()
      throws ReflectiveOperationException {
    assertThat(applicationContext.getBeanNamesForType(CustomRuleSource.class))
        .as("CustomRuleSourceImpl (Task 7) é a única implementação de CustomRuleSource")
        .hasSize(1);

    Field field = RiskScoringService.class.getDeclaredField("customRuleSource");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Optional<CustomRuleSource> injected =
        (Optional<CustomRuleSource>) field.get(riskScoringService);

    assertThat(injected).isPresent();
    assertThat(injected.get()).isInstanceOf(CustomRuleSourceImpl.class);
  }
}
