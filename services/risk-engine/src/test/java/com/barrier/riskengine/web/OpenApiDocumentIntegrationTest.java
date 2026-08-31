package com.barrier.riskengine.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * O contrato público existe e é servido.
 *
 * <p>Em A o produto <b>é</b> a integração: um spec que não sobe não é documentação atrasada, é
 * produto quebrado. Por isso a verificação é de integração e não unitária — o que importa não é a
 * dependência estar no pom, é o documento sair pela porta que o parceiro alcança.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      // o contrato nao depende de pipeline rodando; adiar os jobs deixa o teste sobre o que ele mede
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class OpenApiDocumentIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @LocalServerPort int port;

  @Test
  void serveOContratoNaPortaDeNegocio() {
    String corpo =
        RestClient.create()
            .get()
            .uri("http://localhost:" + port + "/v3/api-docs")
            .retrieve()
            .body(String.class);

    assertThat(corpo).as("spec vazio ou ausente").isNotBlank();
    assertThat(corpo).contains("/v1/assessments");
  }

  private String spec(String grupo) {
    return RestClient.create()
        .get()
        .uri("http://localhost:" + port + "/v3/api-docs/" + grupo)
        .retrieve()
        .body(String.class);
  }

  /**
   * A superficie administrativa fica FORA do documento publicado. Emitir credencial de tenant e
   * ligar/desligar regra regulatoria nao sao capacidades que o parceiro precisa conhecer, e um mapa
   * delas e reconhecimento de graca para quem procurar.
   */
  @Test
  void oContratoDoParceiroNaoExpoeRotaAdministrativa() {
    String parceiro = spec("parceiro");

    assertThat(parceiro).contains("/v1/assessments");
    assertThat(parceiro).doesNotContain("/v1/risk-rules");
    assertThat(parceiro).doesNotContain("/v1/tenants");
    assertThat(parceiro).doesNotContain("/v1/webhook-endpoints");
  }

  @Test
  void oGrupoAdministrativoExisteSeparado() {
    assertThat(spec("admin")).contains("/v1/risk-rules");
  }
}
