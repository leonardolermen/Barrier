package com.barrier.riskengine.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.tenant.service.ApiKeyService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Fronteira HTTP da ingestão comportamental — o módulo não tinha nenhuma.
 *
 * <p><b>É a lacuna de categoria que deixou o F8 inacessível.</b> {@code BehaviorEventServiceTest}
 * cobre o domínio com qualidade; a camada web não tinha teste algum, e por isso ninguém percebeu
 * que {@code /v1/behavior-events} estava fora do filtro de autenticação — todo POST respondia 409
 * com uma mensagem interna, para qualquer chamador, desde a entrega.
 *
 * <p>Cobre as três coisas que um teste de fronteira precisa afirmar e um teste de domínio não
 * alcança: <b>auth</b>, <b>escopo de tenant</b> e <b>contrato de status</b>.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000",
      "barrier.watchlist.refresh-cron=0 0 3 1 1 ?"
    })
@Testcontainers
class BehaviorEventApiIntegrationTest {

  private static final String DOCUMENTO = "11144477735";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Value("${local.server.port}")
  int port;

  @Autowired ApiKeyService apiKeyService;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  /** Só o tenant `default` vem semeado (V009); os demais são criados pelo teste. */
  @org.junit.jupiter.api.BeforeEach
  void criaTenantsDoTeste() {
    for (String id : new String[] {"tenant-a", "tenant-b", "tenant-400"}) {
      jdbc.update(
          "INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
          id,
          "Tenant de teste " + id);
    }
  }

  @Test
  void semCredencialRecusaCom401() {
    var response =
        client()
            .post()
            .uri("/v1/behavior-events")
            .contentType(MediaType.APPLICATION_JSON)
            .body(evento("sem-credencial-1"))
            .exchange((req, res) -> res.getStatusCode(), false);

    assertThat(response.value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  @Test
  void credencialInvalidaRecusaCom401() {
    var response =
        client()
            .post()
            .uri("/v1/behavior-events")
            .header("Authorization", "Bearer brr_inexistente_naoexiste")
            .contentType(MediaType.APPLICATION_JSON)
            .body(evento("credencial-invalida-1"))
            .exchange((req, res) -> res.getStatusCode(), false);

    assertThat(response.value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  /**
   * <b>202, não 201.</b> O fato foi aceito; nada foi decidido a partir dele. Um 201 prometeria um
   * recurso com efeito, e o acervo comportamental é deliberadamente inerte — nenhuma regra o lê
   * ainda, e a política de disparo é entrega própria.
   */
  @Test
  void fatoAceitoResponde202() {
    var response = postAutenticado(evento("aceito-1"));

    assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.ACCEPTED.value());
    assertThat(response.getBody()).containsEntry("duplicate", false);
  }

  /**
   * Reenvio do mesmo {@code sourceEventId} responde <b>202 com {@code duplicate=true}</b>, não 409.
   * Reenviar precisa ser seguro e barato: se doer, o parceiro evita reenviar e perde fato de
   * verdade — que é o oposto do que um acervo de fatos quer.
   */
  @Test
  void reenvioDuplicadoResponde202ComMarcacao() {
    postAutenticado(evento("duplicado-1"));
    var segundo = postAutenticado(evento("duplicado-1"));

    assertThat(segundo.getStatusCode().value()).isEqualTo(HttpStatus.ACCEPTED.value());
    assertThat(segundo.getBody()).containsEntry("duplicate", true);
  }

  @Test
  void corpoInvalidoResponde400() {
    var response =
        client()
            .post()
            .uri("/v1/behavior-events")
            .header("Authorization", "Bearer " + apiKey("tenant-400"))
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("documentType", "CPF")) // faltam os obrigatórios
            .exchange((req, res) -> res.getStatusCode(), false);

    assertThat(response.value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
  }

  /**
   * <b>Escopo de tenant.</b> O mesmo {@code sourceEventId} vindo de dois parceiros são dois fatos
   * distintos: a idempotência é por tenant, e tratá-la globalmente faria o evento de um parceiro
   * silenciar o de outro — perda de fato, não dedup.
   */
  @Test
  void aIdempotenciaEhEscopadaPorTenant() {
    String chaveCompartilhada = "mesma-chave-dois-tenants";
    var doPrimeiro = postComoTenant("tenant-a", evento(chaveCompartilhada));
    var doSegundo = postComoTenant("tenant-b", evento(chaveCompartilhada));

    assertThat(doPrimeiro.getBody()).containsEntry("duplicate", false);
    assertThat(doSegundo.getBody())
        .as("o fato do segundo parceiro foi engolido pela chave do primeiro")
        .containsEntry("duplicate", false);
  }

  private Map<String, Object> evento(String sourceEventId) {
    return Map.of(
        "documentType", "CPF",
        "document", DOCUMENTO,
        "name", "Teste Fronteira",
        "eventType", "PIX_ENVIADO",
        "occurredAt", Instant.now().toString(),
        "sourceEventId", sourceEventId);
  }

  @SuppressWarnings("unchecked")
  private org.springframework.http.ResponseEntity<Map<String, Object>> postAutenticado(
      Map<String, Object> body) {
    return postComoTenant("default", body);
  }

  @SuppressWarnings("unchecked")
  private org.springframework.http.ResponseEntity<Map<String, Object>> postComoTenant(
      String tenantId, Map<String, Object> body) {
    return client()
        .post()
        .uri("/v1/behavior-events")
        .header("Authorization", "Bearer " + apiKey(tenantId))
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity((Class<Map<String, Object>>) (Class<?>) Map.class);
  }

  private String apiKey(String tenantId) {
    return apiKeyService.issue(tenantId, "teste-fronteira").presentedValue();
  }

  private RestClient client() {
    return RestClient.create("http://localhost:" + port);
  }
}
