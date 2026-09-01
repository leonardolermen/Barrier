package com.barrier.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A webhook-api tambem publica contrato — e o dela e inteiramente administrativo.
 *
 * <p>Registrar destino e rotacionar segredo sao operacoes da operacao, nao do parceiro: por isso
 * nao ha grupo "parceiro" aqui. O que o parceiro precisa saber sobre webhook (como verificar o
 * HMAC, o que fazer com X-Barrier-Signature-Previous) e guia de integracao, nao referencia de API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OpenApiDocumentIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @LocalServerPort int port;

  @Test
  void serveOContrato() {
    String corpo =
        RestClient.create()
            .get()
            .uri("http://localhost:" + port + "/v3/api-docs")
            .retrieve()
            .body(String.class);

    assertThat(corpo).isNotBlank();
    assertThat(corpo).contains("/v1/webhook-endpoints");
  }
}
