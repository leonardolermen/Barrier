package com.barrier.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.commons.event.EventEnvelope;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * Teste de carga do caminho quente do serviço: Kafka → {@code AssessmentCompletedListener} →
 * insert em {@code deliveries} → POST assinado no endpoint do cliente.
 *
 * <p>Não é teste de regressão — está fora do {@code ./mvnw test} pela tag {@code load} (ver
 * {@code excludedGroups} no pom raiz). Rode com:
 *
 * <pre>./mvnw -pl services/webhook-api test -Dgroups=load -DexcludedGroups= \
 *   -Dload.events=2000 -Dload.sink-latency-ms=50 -Dload.partitions=4 -Dload.concurrency=4</pre>
 *
 * <p>O que ele mede de verdade: a entrega é um POST <b>síncrono na thread do listener</b>, então a
 * vazão é limitada por {@code concurrency × (1 / latência do endpoint do cliente)} — subir
 * {@code load.sink-latency-ms} com {@code concurrency=1} expõe o bloqueio de cabeça de fila que
 * um cliente lento causa em todos os outros tenants da mesma partição.
 */
@SpringBootTest
@Testcontainers
@Tag("load")
class WebhookLoadTest {

  private static final int EVENTS = Integer.getInteger("load.events", 1000);
  private static final int SINK_LATENCY_MS = Integer.getInteger("load.sink-latency-ms", 0);
  private static final int PARTITIONS = Integer.getInteger("load.partitions", 1);
  private static final int CONCURRENCY = Integer.getInteger("load.concurrency", 1);
  private static final int TIMEOUT_MINUTES = Integer.getInteger("load.timeout-minutes", 10);

  private static final String TOPIC = "barrier.assessment.completed";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  /** Instante de chegada no endpoint do cliente, por eventId — a ponta final da latência. */
  static final Map<String, Long> ARRIVALS = new ConcurrentHashMap<>();

  static final AtomicInteger DUPLICATES = new AtomicInteger();

  private static final HttpServer SINK = startSink();

  private static HttpServer startSink() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
      // Pool generoso de propósito: o gargalo a medir é o serviço, não o receptor.
      server.setExecutor(Executors.newFixedThreadPool(64));
      server.createContext(
          "/webhook",
          exchange -> {
            String eventId = exchange.getRequestHeaders().getFirst("X-Barrier-Event-Id");
            exchange.getRequestBody().readAllBytes();
            if (SINK_LATENCY_MS > 0) {
              try {
                Thread.sleep(SINK_LATENCY_MS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            if (ARRIVALS.putIfAbsent(eventId, System.nanoTime()) != null) {
              DUPLICATES.incrementAndGet();
            }
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
    createTopic();
    registry.add(
        "barrier.webhook.target-url",
        () -> "http://localhost:" + SINK.getAddress().getPort() + "/webhook");
    registry.add("barrier.webhook.secret", () -> "load-secret");
    registry.add("spring.kafka.listener.concurrency", () -> CONCURRENCY);
  }

  /** O container cria tópico com 1 partição sozinho; a carga precisa do fan-out explícito. */
  private static void createTopic() {
    try (var admin =
        org.apache.kafka.clients.admin.Admin.create(
            Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(new org.apache.kafka.clients.admin.NewTopic(TOPIC, PARTITIONS, (short) 1)))
          .all()
          .get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (Exception e) {
      throw new IllegalStateException("falha ao criar o tópico de carga", e);
    }
  }

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper objectMapper;

  @Test
  void vazaoDeEntregaSobCarga() {
    Map<String, Long> published = new ConcurrentHashMap<>(EVENTS);

    long start = System.nanoTime();
    for (int i = 0; i < EVENTS; i++) {
      EventEnvelope envelope =
          new EventEnvelope(
              UUID.randomUUID(),
              TOPIC,
              "load-" + i,
              java.time.Instant.now(),
              1,
              "{\"status\":\"APROVADO\",\"tenantId\":\"default\",\"seq\":" + i + "}");
      published.put(envelope.eventId().toString(), System.nanoTime());
      // A chave é o assessmentId, como em produção — é ela que distribui entre as partições.
      kafka.send(TOPIC, envelope.assessmentId(), objectMapper.writeValueAsString(envelope));
    }
    kafka.flush();
    long publishedAt = System.nanoTime();

    Awaitility.await()
        .atMost(Duration.ofMinutes(TIMEOUT_MINUTES))
        .pollInterval(Duration.ofMillis(500))
        .until(() -> ARRIVALS.size() >= EVENTS);
    long end = System.nanoTime();

    long[] latencies =
        published.entrySet().stream()
            .filter(e -> ARRIVALS.containsKey(e.getKey()))
            .mapToLong(e -> (ARRIVALS.get(e.getKey()) - e.getValue()) / 1_000_000)
            .sorted()
            .toArray();

    double wallSeconds = (end - start) / 1e9;
    Integer delivered =
        jdbc.queryForObject(
            "select count(*) from webhook.deliveries where status = 'DELIVERED'", Integer.class);
    Integer rows =
        jdbc.queryForObject("select count(*) from webhook.deliveries", Integer.class);

    System.out.printf(
        // Sem acento: o console do build nem sempre esta em UTF-8, e relatorio ilegivel nao serve.
        """

        ===== webhook-api - teste de carga =====
        eventos              : %d
        particoes/concorrencia: %d / %d
        latencia do sink     : %d ms
        publicacao           : %.2f s (%.0f ev/s)
        entrega completa em  : %.2f s
        vazao de entrega     : %.0f ev/s
        latencia e2e p50     : %d ms
        latencia e2e p95     : %d ms
        latencia e2e p99     : %d ms
        latencia e2e max     : %d ms
        linhas em deliveries : %d (DELIVERED: %d)
        POSTs duplicados     : %d
        ========================================
        %n""",
        EVENTS,
        PARTITIONS,
        CONCURRENCY,
        SINK_LATENCY_MS,
        (publishedAt - start) / 1e9,
        EVENTS / ((publishedAt - start) / 1e9),
        wallSeconds,
        EVENTS / wallSeconds,
        percentile(latencies, 50),
        percentile(latencies, 95),
        percentile(latencies, 99),
        latencies[latencies.length - 1],
        rows,
        delivered,
        DUPLICATES.get());

    // Correção sob carga, não performance: números viram meta depois de medidos em ambiente fixo.
    assertThat(ARRIVALS).hasSize(EVENTS);
    assertThat(DUPLICATES.get()).as("entrega duplicada do mesmo evento").isZero();
    assertThat(rows).isEqualTo(EVENTS);
    assertThat(delivered).isEqualTo(EVENTS);
  }

  private static long percentile(long[] sorted, int p) {
    int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
  }
}
