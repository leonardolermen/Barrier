package com.barrier.riskengine.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * O actuator não pode ser servido pela porta pública da API.
 *
 * <p><b>O que estava exposto.</b> {@code management.endpoints.web.exposure.include} publicava
 * {@code health,info,metrics,prometheus} na mesma porta da API de negócio, e <b>nenhum filtro cobre
 * {@code /actuator}</b> — não há Spring Security no classpath, e tanto o
 * {@link TenantAuthenticationFilter} quanto o {@link AdminApiKeyFilter} só olham {@code /v1/}.
 * Qualquer chamador anônimo lia {@code /actuator/prometheus}, que entrega volume por tenant, taxa
 * de aprovação e recusa, e profundidade de fila: inteligência competitiva sobre os parceiros — e
 * sinal de fraude, porque um atacante observa em agregado se a tentativa dele passou.
 *
 * <p><b>A correção é porta separada</b>, não filtro: o actuator sobe num servidor próprio que o
 * {@code Service} do Kubernetes não publica. As probes do kubelet e o scrape do Prometheus falam
 * com ela dentro do cluster; de fora, ela não existe. Filtro seria mais frágil — dependeria de
 * acertar o padrão de rota de novo, que é exatamente como {@code /v1/mesa} ficou aberto.
 *
 * <p>O teste afirma a propriedade pela porta pública, que é o que um atacante alcança.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000",
      "barrier.watchlist.refresh-cron=0 0 3 1 1 ?",
      // Porta 0 = aleatória também para o management, para o teste não brigar por porta fixa.
      "management.server.port=0"
    })
@Testcontainers
class ActuatorPortIsolationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Value("${local.server.port}")
  int publicPort;

  @Value("${local.management.port}")
  int managementPort;

  @Test
  void aPortaPublicaNaoServeOActuator() {
    for (String path :
        new String[] {
          "/actuator", "/actuator/health", "/actuator/info", "/actuator/metrics",
          "/actuator/prometheus"
        }) {
      assertThat(statusNaPortaPublica(path))
          .as("%s exposto na porta da API de negócio", path)
          .isEqualTo(404);
    }
  }

  /** A porta de gestão continua servindo — senão as probes do Kubernetes quebram em silêncio. */
  @Test
  void aPortaDeGestaoServeAsProbes() {
    assertThat(statusNaPortaDeGestao("/actuator/health/liveness")).isEqualTo(200);
    assertThat(statusNaPortaDeGestao("/actuator/health/readiness")).isEqualTo(200);
  }

  /** As duas portas precisam ser mesmo distintas: iguais, o primeiro teste passaria por acidente. */
  @Test
  void asPortasSaoDistintas() {
    assertThat(managementPort).isNotEqualTo(publicPort);
  }

  private int statusNaPortaPublica(String path) {
    return status("http://localhost:" + publicPort + path);
  }

  private int statusNaPortaDeGestao(String path) {
    return status("http://localhost:" + managementPort + path);
  }

  private int status(String url) {
    return RestClient.create()
        .get()
        .uri(url)
        .exchange((request, response) -> response.getStatusCode(), false)
        .value();
  }
}
