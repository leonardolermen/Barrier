package com.barrier.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.commons.event.EventEnvelope;
import com.barrier.webhook.repository.DeliveryRepository;
import com.barrier.webhook.service.WebhookDeliveryService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Fluxo de entrega fim-a-fim: dado um evento, o serviço assina e faz POST no endpoint do
 * cliente (um servidor HTTP embutido), e a entrega é registrada. Requer Docker.
 */
@SpringBootTest
@Testcontainers
class WebhookDeliveryIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  private static final HttpServer SERVER = startServer();
  static volatile String receivedSignature;
  static volatile String receivedEventId;
  static volatile String receivedBody;

  private static HttpServer startServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
      server.createContext(
          "/webhook",
          exchange -> {
            receivedSignature = exchange.getRequestHeaders().getFirst("X-Barrier-Signature");
            receivedEventId = exchange.getRequestHeaders().getFirst("X-Barrier-Event-Id");
            receivedBody =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add(
        "barrier.webhook.target-url",
        () -> "http://localhost:" + SERVER.getAddress().getPort() + "/webhook");
    registry.add("barrier.webhook.secret", () -> "test-secret");
  }

  @Autowired WebhookDeliveryService service;
  @Autowired DeliveryRepository repository;

  @Test
  void entregaAssinaERegistra() {
    EventEnvelope envelope =
        EventEnvelope.of("barrier.assessment.completed", "abc-123", 1, "{\"status\":\"APROVADO\"}");

    service.onEvent(envelope);

    assertThat(receivedBody).contains("APROVADO");
    assertThat(receivedSignature).startsWith("sha256=");
    assertThat(receivedEventId).isEqualTo(envelope.eventId().toString());
    assertThat(repository.existsByEventId(envelope.eventId())).isTrue();
  }
}
