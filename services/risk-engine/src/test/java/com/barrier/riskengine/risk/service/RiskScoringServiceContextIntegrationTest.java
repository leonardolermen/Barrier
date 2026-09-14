package com.barrier.riskengine.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.risk.rule.interfaces.CustomRuleSource;
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
 * Sobe o contexto real da aplicação e confirma que {@link RiskScoringService} é construído
 * **sem** nenhum bean {@link CustomRuleSource} presente — o estado em que esta tarefa deixa o
 * repositório, já que a única implementação (Task 7) ainda não existe.
 *
 * <p><b>Por que este teste existe, e não só o unitário.</b> {@code RiskScoringServiceTest} chama
 * {@code new RiskScoringService(...)} direto, sem passar pelo container — prova que a classe
 * funciona, não que ela pode ser <b>construída</b> no ambiente real. Se o construtor exigisse
 * {@code CustomRuleSource} cru em vez de {@code Optional<CustomRuleSource>}, o {@code
 * RiskEngineApplication} (que escaneia {@code com.barrier} inteiro e instancia {@code @Service}
 * ansiosamente) não teria bean para injetar e o contexto inteiro falharia na subida —
 * exatamente o defeito que {@code FieldCatalogConfig} teve com {@code FieldCatalog} na Task 4
 * (107 erros de {@code ApplicationContext} em 30 classes de teste, descobertos só na suíte
 * completa, 25 minutos depois).
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

  @Test
  void sobe_sem_nenhum_bean_de_custom_rule_source() {
    assertThat(applicationContext.getBeanNamesForType(CustomRuleSource.class))
        .as(
            "Task 7 é a única implementação de CustomRuleSource e ainda não existe — o "
                + "contexto tem de subir mesmo assim, resolvendo Optional<CustomRuleSource> "
                + "para vazio")
        .isEmpty();
  }
}
