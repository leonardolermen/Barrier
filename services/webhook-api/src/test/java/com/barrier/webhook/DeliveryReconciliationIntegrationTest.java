package com.barrier.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.commons.event.EventEnvelope;
import com.barrier.webhook.repository.DeliveryRepository;
import com.barrier.webhook.service.DeliveryReconciliationJob;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * Reconciliação contra Kafka e Postgres reais.
 *
 * <p>O listener fica desligado ({@code auto-startup=false}) para simular exatamente o cenário que
 * este job existe para cobrir: eventos de decisão que passaram pelo tópico enquanto ninguém
 * conseguia consumi-los — consumidor fora do ar, mensagem parada na DLT, falha longa demais para o
 * backoff. Sem reconciliação, esses clientes nunca receberiam o resultado do KYC.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
class DeliveryReconciliationIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  private static final HttpServer SINK = startSink();

  private static HttpServer startSink() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
      server.createContext(
          "/webhook",
          exchange -> {
            exchange.getRequestBody().readAllBytes();
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
        () -> "http://localhost:" + SINK.getAddress().getPort() + "/webhook");
    registry.add("barrier.webhook.secret", () -> "test-secret");
  }

  @Autowired KafkaTemplate<String, String> kafkaTemplate;
  @Autowired DeliveryReconciliationJob job;
  @Autowired DeliveryRepository repository;
  @Autowired ObjectMapper objectMapper;

  private EventEnvelope publica(String assessmentId) {
    EventEnvelope envelope =
        EventEnvelope.of(
            "barrier.assessment.completed",
            assessmentId,
            1,
            "{\"status\":\"APROVADO\",\"tenantId\":\"default\"}");
    kafkaTemplate
        .send("barrier.assessment.completed", assessmentId, objectMapper.writeValueAsString(envelope))
        .join();
    return envelope;
  }

  @Test
  void recuperaDecisoesQueNaoViraramEntrega() {
    EventEnvelope primeiro = publica("recon-1");
    EventEnvelope segundo = publica("recon-2");
    assertThat(repository.existsByEventId(primeiro.eventId())).isFalse();

    int recuperados = job.reconcileSince(Instant.now().minus(Duration.ofMinutes(10)));

    assertThat(recuperados).isEqualTo(2);
    assertThat(repository.existsByEventId(primeiro.eventId())).isTrue();
    assertThat(repository.existsByEventId(segundo.eventId())).isTrue();
  }

  /** Rodar de novo não pode duplicar a entrega — o cliente receberia o veredito duas vezes. */
  @Test
  void reconciliacaoRepetidaNaoRecriaEntrega() {
    publica("recon-3");
    job.reconcileSince(Instant.now().minus(Duration.ofMinutes(10)));

    int segundaPassada = job.reconcileSince(Instant.now().minus(Duration.ofMinutes(10)));

    assertThat(segundaPassada).isZero();
  }
}
