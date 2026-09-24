package com.barrier.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.commons.event.EventEnvelope;
import com.barrier.webhookdelivery.client.HmacSigner;
import com.barrier.webhookdelivery.domain.WebhookEndpoint;
import com.barrier.webhookdelivery.service.WebhookEndpointService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * Fluxo de entrega fim-a-fim: um evento publicado no tópico chega assinado ao endpoint do cliente.
 * Requer Docker.
 *
 * <p>Versão enxuta do teste que existia antes da extração (media o serviço inteiro, dono da
 * máquina de entrega); agora só prova que a ligação Kafka → {@code AssessmentCompletedListener} →
 * {@code com.barrier:webhook-delivery} → POST assinado continua de pé, com os headers
 * {@code X-Barrier-*} (prefixo configurado em {@code webhook-delivery.headers.prefix}) e a
 * assinatura verificável com o {@link HmacSigner} da própria lib e o segredo do endpoint. Os
 * outros cenários (N endpoints, filtro por evento, idempotência, rotação) já são cobertos na
 * suíte da lib.
 */
@SpringBootTest
@Testcontainers
class WebhookDeliveryIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  record Recebido(String body, Map<String, String> headers) {}

  private static final Map<String, Recebido> RECEBIDOS = new ConcurrentHashMap<>();

  private static final HttpServer SINK = startSink();

  private static HttpServer startSink() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
      server.createContext(
          "/webhook",
          exchange -> {
            String eventId = exchange.getRequestHeaders().getFirst("X-Barrier-Event-Id");
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.getFirst()));
            RECEBIDOS.put(eventId, new Recebido(body, headers));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Autowired org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;
  @Autowired WebhookEndpointService endpointService;
  @Autowired HmacSigner signer;
  @Autowired ObjectMapper objectMapper;

  @Test
  void eventoPublicadoChegaAssinadoAoEndpointDoTenant() {
    WebhookEndpoint endpoint =
        endpointService.registerSingle(
            "acme", "http://localhost:" + SINK.getAddress().getPort() + "/webhook");

    EventEnvelope envelope =
        EventEnvelope.of(
            "barrier.assessment.completed",
            "assess-1",
            1,
            "{\"status\":\"APROVADO\",\"tenantId\":\"acme\",\"subjectId\":\"sub-1\"}");
    kafkaTemplate
        .send(
            "barrier.assessment.completed",
            envelope.assessmentId(),
            objectMapper.writeValueAsString(envelope))
        .join();

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(RECEBIDOS).containsKey(envelope.eventId().toString()));

    Recebido recebido = RECEBIDOS.get(envelope.eventId().toString());
    // com.sun.net.httpserver.Headers normaliza para Título-Caso na leitura (getFirst), mas o Map
    // que construímos com forEach preserva a capitalização que veio na conexão HTTP/2 (minúscula
    // aqui) — daí o lookup ser ignorando caixa, o que é o que importa: HTTP headers não são
    // case-sensitive.
    String signatureKey =
        recebido.headers().keySet().stream()
            .filter(k -> k.equalsIgnoreCase("X-Barrier-Signature"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("sem header X-Barrier-Signature: " + recebido.headers()));
    String eventIdKey =
        recebido.headers().keySet().stream()
            .filter(k -> k.equalsIgnoreCase("X-Barrier-Event-Id"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("sem header X-Barrier-Event-Id: " + recebido.headers()));
    assertThat(recebido.headers().get(eventIdKey)).isEqualTo(envelope.eventId().toString());

    String assinatura = recebido.headers().get(signatureKey);
    long t = Long.parseLong(assinatura.substring(2, assinatura.indexOf(',')));
    assertThat(assinatura)
        .isEqualTo(signer.sign(recebido.body(), endpoint.secret(), Instant.ofEpochSecond(t)));
  }
}
