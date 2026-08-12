package com.barrier.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.webhook.controller.dto.RegisterEndpointRequest;
import com.barrier.webhook.controller.dto.WebhookEndpointResponse;
import com.barrier.webhook.controller.dto.WebhookEndpointSecretResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Registro de endpoints pela API administrativa. O que está sob teste é principalmente a trava:
 * quem escreve aqui decide para onde vai o veredito de KYC de um parceiro, então sem
 * {@code X-Admin-Key} nada passa.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "barrier.admin.api-key=chave-de-admin-para-teste-com-32-chars")
@Testcontainers
class WebhookEndpointApiIntegrationTest {

  private static final String ADMIN_KEY = "chave-de-admin-para-teste-com-32-chars";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Value("${local.server.port}")
  int port;

  private RestClient client(String adminKey) {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:" + port);
    if (adminKey != null) {
      builder.defaultHeader("X-Admin-Key", adminKey);
    }
    return builder.build();
  }

  private ResponseEntity<WebhookEndpointSecretResponse> register(
      String adminKey, String tenantId, String url) {
    return client(adminKey)
        .put()
        .uri("/v1/webhook-endpoints/" + tenantId)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RegisterEndpointRequest(url))
        .retrieve()
        .toEntity(WebhookEndpointSecretResponse.class);
  }

  @Test
  void registraEConsultaOEndpointDoTenant() {
    ResponseEntity<WebhookEndpointSecretResponse> criado =
        register(ADMIN_KEY, "acme", "https://acme.example/hook");

    assertThat(criado.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(criado.getBody().targetUrl()).isEqualTo("https://acme.example/hook");
    assertThat(criado.getBody().active()).isTrue();

    ResponseEntity<WebhookEndpointResponse> lido =
        client(ADMIN_KEY)
            .get()
            .uri("/v1/webhook-endpoints/acme")
            .retrieve()
            .toEntity(WebhookEndpointResponse.class);
    assertThat(lido.getBody().targetUrl()).isEqualTo("https://acme.example/hook");
    assertThat(lido.getBody().secretConfigured()).isTrue();
  }

  /**
   * O segredo aparece uma vez, no registro — como a emissão de API key. Devolvê-lo em toda consulta
   * transformaria qualquer leitura do registro em vazamento do que permite forjar callbacks.
   */
  @Test
  void registroDevolveOSegredoUmaVezEOGetNuncaOExpoe() {
    ResponseEntity<WebhookEndpointSecretResponse> criado =
        register(ADMIN_KEY, "com-segredo", "https://parceiro.example/hook");

    assertThat(criado.getBody().secret()).isNotBlank();

    String corpoDoGet =
        client(ADMIN_KEY)
            .get()
            .uri("/v1/webhook-endpoints/com-segredo")
            .retrieve()
            .body(String.class);
    assertThat(corpoDoGet).doesNotContain(criado.getBody().secret());
    assertThat(client(ADMIN_KEY).get().uri("/v1/webhook-endpoints").retrieve().body(String.class))
        .doesNotContain(criado.getBody().secret());
  }

  /** Atualizar a URL não troca o segredo — o cliente não pediu rotação e pararia de verificar. */
  @Test
  void atualizarAUrlPreservaOSegredo() {
    String original = register(ADMIN_KEY, "preserva", "https://um.example/hook").getBody().secret();

    String depois = register(ADMIN_KEY, "preserva", "https://outro.example/hook").getBody().secret();

    assertThat(depois).isEqualTo(original);
  }

  @Test
  void rotacaoTrocaOSegredoEAbreAJanelaDoAnterior() {
    String original = register(ADMIN_KEY, "rotaciona", "https://parceiro.example/hook").getBody().secret();

    ResponseEntity<WebhookEndpointSecretResponse> rotacionado =
        client(ADMIN_KEY)
            .post()
            .uri("/v1/webhook-endpoints/rotaciona/rotate-secret")
            .retrieve()
            .toEntity(WebhookEndpointSecretResponse.class);

    assertThat(rotacionado.getBody().secret()).isNotBlank().isNotEqualTo(original);
    assertThat(rotacionado.getBody().previousSecretUntil()).isNotNull();
  }

  @Test
  void rotacaoDeTenantDesconhecidoResponde404() {
    assertThatThrownBy(
            () ->
                client(ADMIN_KEY)
                    .post()
                    .uri("/v1/webhook-endpoints/nunca-registrado/rotate-secret")
                    .retrieve()
                    .toBodilessEntity())
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            e ->
                assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  /** Rotação também é operação administrativa: sem chave, ninguém troca o segredo de ninguém. */
  @Test
  void rotacaoSemChaveDeAdminResponde401() {
    register(ADMIN_KEY, "protegido", "https://parceiro.example/hook");

    assertThatThrownBy(
            () ->
                client(null)
                    .post()
                    .uri("/v1/webhook-endpoints/protegido/rotate-secret")
                    .retrieve()
                    .toBodilessEntity())
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            e ->
                assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void semChaveDeAdminResponde401() {
    assertThatThrownBy(() -> register(null, "invasor", "https://invasor.example/hook"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            e ->
                assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));

    assertThatThrownBy(() -> register("chave-errada", "invasor", "https://invasor.example/hook"))
        .isInstanceOf(HttpClientErrorException.class);
  }

  @Test
  void urlSemTlsResponde400() {
    assertThatThrownBy(() -> register(ADMIN_KEY, "acme", "http://acme.example/hook"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            e ->
                assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void desativarPreservaOCadastroEDevolve404ParaTenantDesconhecido() {
    register(ADMIN_KEY, "para-desativar", "https://parceiro.example/hook");

    ResponseEntity<WebhookEndpointResponse> desativado =
        client(ADMIN_KEY)
            .delete()
            .uri("/v1/webhook-endpoints/para-desativar")
            .retrieve()
            .toEntity(WebhookEndpointResponse.class);
    assertThat(desativado.getBody().active()).isFalse();

    assertThatThrownBy(
            () ->
                client(ADMIN_KEY)
                    .delete()
                    .uri("/v1/webhook-endpoints/nunca-registrado")
                    .retrieve()
                    .toBodilessEntity())
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            e ->
                assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }
}
