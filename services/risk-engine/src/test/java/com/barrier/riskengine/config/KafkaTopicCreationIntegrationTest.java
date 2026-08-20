package com.barrier.riskengine.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Os tópicos precisam existir <b>de fato no broker</b>, com as partições declaradas.
 *
 * <p><b>Por que este teste existe, e não só o unitário.</b> A primeira versão do
 * {@link KafkaTopicsConfig} expunha um bean {@code List<NewTopic>}. O teste unitário passava — a
 * lista tinha os três nomes certos e as partições certas — e <b>nenhum tópico era criado</b>: o
 * {@link KafkaAdmin} varre o contexto por beans do tipo {@code NewTopic} ou
 * {@code KafkaAdmin.NewTopics}, e um {@code List<NewTopic>} não é nem um nem outro. O bean era
 * ignorado em silêncio.
 *
 * <p>O sintoma em produção seria idêntico ao problema que a config veio resolver: tópico
 * auto-criado pelo broker com <b>uma</b> partição, um pod consumindo, quatro ociosos — só que
 * agora com uma classe de configuração dando a impressão de que o assunto estava resolvido.
 * Testar o valor de retorno de um método prova aritmética; o que precisava de prova era o
 * <b>wiring</b>.
 *
 * <p>Descoberto subindo 5 réplicas num cluster kind, não pelo build. É o argumento do plano de
 * escala horizontal na forma mais literal.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class KafkaTopicCreationIntegrationTest {

  private static final int PARTICOES_ESPERADAS = 6;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired KafkaAdmin kafkaAdmin;

  @Test
  void criaOsTopicosNoBrokerComAsParticoesDeclaradas() throws Exception {
    Set<String> esperados =
        Set.of(
            "barrier.assessment.completed",
            "barrier.subject.risk_level_changed",
            "barrier.behavior.recorded");

    try (AdminClient admin =
        AdminClient.create(
            Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {

      Map<String, TopicDescription> descricoes =
          admin.describeTopics(esperados).allTopicNames().get();

      assertThat(descricoes.keySet())
          .as("tópicos não criados no broker — o bean de topologia não foi reconhecido")
          .containsExactlyInAnyOrderElementsOf(esperados);

      assertThat(descricoes.values())
          .allSatisfy(
              d ->
                  assertThat(d.partitions())
                      .as(
                          "tópico %s com %d partições limita o consumo a %d pods",
                          d.name(), d.partitions().size(), d.partitions().size())
                      .hasSize(PARTICOES_ESPERADAS));
    }
  }
}
