package com.barrier.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * O deploy da extração contra um banco que JÁ TEM dados. Os outros testes sobem banco vazio, onde a
 * V009 move tabelas vazias e qualquer ordem entre o Flyway do serviço e o da lib passa: foi assim
 * que a 0.1.0 da lib desligou o Flyway do serviço sem nenhum teste notar (o log mostrava só "applied
 * 1 migration to schema webhook_delivery", nunca as 9 de {@code webhook}).
 *
 * <p>Aqui o banco para na V008 com a forma de produção — endpoint de antes da V005 (sem segredo),
 * entrega sem tenant, entrega de tenant sem endpoint — e só então a aplicação sobe. O contexto é
 * iniciado à mão com {@link SpringApplication} porque o seed precisa acontecer DEPOIS do container
 * e ANTES do contexto, o que {@code @SpringBootTest} não oferece de forma limpa.
 */
@Testcontainers
class V009MigracaoComDadosIntegrationTest {

  private static final String SEGREDO_LEGADO = "segredo-global-legado-de-teste";
  private static final String ID_SINTETICO = "00000000-0000-0000-0000-000000000000";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  static ConfigurableApplicationContext contexto;
  static JdbcTemplate jdbc;

  static final UUID ENTREGA_C = UUID.randomUUID();
  static final UUID ENTREGA_D = UUID.randomUUID();
  static final UUID ENTREGA_E = UUID.randomUUID();

  @BeforeAll
  static void semeiaNaV008ESobeAAplicacao() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(ds)
        .schemas("webhook")
        .defaultSchema("webhook")
        .locations("classpath:db/migration")
        .target("8")
        .load()
        .migrate();

    jdbc = new JdbcTemplate(ds);
    // (a) endpoint de antes da V005: sem segredo, assinava com o global.
    jdbc.update(
        "INSERT INTO webhook.webhook_endpoints (tenant_id, target_url) VALUES ('legado', 'https://legado.example/hook')");
    // (b) endpoint com segredo próprio.
    jdbc.update(
        "INSERT INTO webhook.webhook_endpoints (tenant_id, target_url, secret) VALUES ('acme', 'https://acme.example/hook', 'segredo-da-acme')");
    // next_attempt_at no futuro: o poller não pode mexer em (c) antes das asserções.
    String entrega =
        "INSERT INTO webhook.deliveries (id, event_id, assessment_id, target_url, payload, status,"
            + " attempts, next_attempt_at, created_at, tenant_id)"
            + " VALUES (?, ?, 'a-1', 'https://x.example', '{}', 'PENDING', 0,"
            + " now() + interval '1 day', now(), ?)";
    jdbc.update(entrega, ENTREGA_C, UUID.randomUUID(), "acme"); // (c)
    jdbc.update(entrega, ENTREGA_D, UUID.randomUUID(), null); // (d) sem tenant
    jdbc.update(entrega, ENTREGA_E, UUID.randomUUID(), "sem-endpoint"); // (e)

    contexto =
        new SpringApplication(WebhookApplication.class)
            .run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.flyway.placeholders[legacy_webhook_secret]=" + SEGREDO_LEGADO,
                "--webhook-delivery.scheduler.enabled=false",
                "--server.port=0",
                "--management.server.port=0");
  }

  @AfterAll
  static void fecha() {
    if (contexto != null) {
      contexto.close();
    }
  }

  @Test
  void oFlywayDoServicoRodaAV009EALibBaseiaDepois() {
    assertThat(
            jdbc.queryForList(
                "SELECT version::int FROM webhook.flyway_schema_history WHERE success AND version IS NOT NULL",
                Integer.class))
        .contains(8, 9);
    assertThat(
            jdbc.queryForList(
                "SELECT version::int FROM webhook_delivery.flyway_schema_history_webhook_delivery"
                    + " WHERE success AND version IS NOT NULL",
                Integer.class))
        .containsExactly(1);
  }

  @Test
  void endpointsGanhamSegredoEInscricaoEmTudo() {
    List<Map<String, Object>> endpoints =
        jdbc.queryForList(
            "SELECT tenant_id, secret, events::text AS events FROM webhook_delivery.webhook_endpoints");
    assertThat(endpoints).hasSize(2);
    assertThat(endpoints).allSatisfy(e -> assertThat(e.get("secret")).isNotNull());
    assertThat(endpoints).allSatisfy(e -> assertThat(e.get("events")).isEqualTo("{*}"));
  }

  @Test
  void endpointDeAntesDaV005HerdaOSegredoGlobalLegado() {
    assertThat(
            jdbc.queryForObject(
                "SELECT secret FROM webhook_delivery.webhook_endpoints WHERE tenant_id = 'legado'",
                String.class))
        .isEqualTo(SEGREDO_LEGADO);
    assertThat(
            jdbc.queryForObject(
                "SELECT secret FROM webhook_delivery.webhook_endpoints WHERE tenant_id = 'acme'",
                String.class))
        .isEqualTo("segredo-da-acme");
  }

  @Test
  void entregaComEndpointContinuaPendenteNoEndpointDoTenant() {
    Map<String, Object> c =
        jdbc.queryForMap(
            "SELECT status, endpoint_id FROM webhook_delivery.deliveries WHERE id = ?", ENTREGA_C);
    UUID endpointAcme =
        jdbc.queryForObject(
            "SELECT id FROM webhook_delivery.webhook_endpoints WHERE tenant_id = 'acme'",
            UUID.class);
    assertThat(c.get("status")).isEqualTo("PENDING");
    assertThat(c.get("endpoint_id")).isEqualTo(endpointAcme);
  }

  @Test
  void entregaSemEndpointMorreComMotivo() {
    for (UUID id : List.of(ENTREGA_D, ENTREGA_E)) {
      Map<String, Object> linha =
          jdbc.queryForMap(
              "SELECT status, last_error, endpoint_id::text AS endpoint_id"
                  + " FROM webhook_delivery.deliveries WHERE id = ?",
              id);
      assertThat(linha.get("status")).isEqualTo("DEAD");
      assertThat(linha.get("last_error")).isEqualTo("sem endpoint na migracao V009");
      assertThat(linha.get("endpoint_id")).isEqualTo(ID_SINTETICO);
    }
  }
}
