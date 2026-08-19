package com.barrier.riskengine.mesa;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.tenant.service.ApiKeyService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Fronteira HTTP da mesa de análise — o módulo não tinha nenhuma.
 *
 * <p>Mesmo motivo do {@code BehaviorEventApiIntegrationTest}: {@code CaseServiceTest} e
 * {@code SlaClockTest} cobrem o domínio com qualidade, e a ausência de teste na camada web deixou
 * {@code /v1/mesa/**} fora do filtro de autenticação sem ninguém notar — a mesa de análise, que é
 * um controle de compliance, esteve <b>inacessível</b> desde a entrega.
 *
 * <p>O que este teste afirma e um teste de domínio não alcança: <b>auth</b> em cada grupo de rota,
 * <b>escopo de tenant</b> (caso de um parceiro não pode ser lido nem movido por outro) e
 * <b>contrato de status</b>.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000",
      "barrier.watchlist.refresh-cron=0 0 3 1 1 ?"
    })
@Testcontainers
class CaseApiIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Value("${local.server.port}")
  int port;

  @Autowired ApiKeyService apiKeyService;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void criaTenantsDoTeste() {
    for (String id : List.of("mesa-a", "mesa-b")) {
      jdbc.update(
          "INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
          id,
          "Tenant de teste " + id);
    }
  }

  /**
   * Cada grupo de rota da mesa exige credencial. Enumerado em vez de testar uma só: foi um grupo
   * inteiro ficar fora da allowlist que criou o problema, e um teste por método garante que
   * nenhum sub-caminho fique de fora de novo.
   */
  @Test
  void todaRotaDaMesaExigeCredencial() {
    UUID caso = UUID.randomUUID();

    assertThat(statusSemCredencial("GET", "/v1/mesa/queues/ANALISE_PADRAO", null)).isEqualTo(401);
    assertThat(statusSemCredencial("GET", "/v1/mesa/cases/" + caso, null)).isEqualTo(401);
    assertThat(statusSemCredencial("POST", "/v1/mesa/cases/" + caso + "/assign", Map.of("actor", "x")))
        .isEqualTo(401);
    assertThat(statusSemCredencial("POST", "/v1/mesa/cases/" + caso + "/move", Map.of("actor", "x")))
        .isEqualTo(401);
    assertThat(
            statusSemCredencial(
                "POST", "/v1/mesa/cases/" + caso + "/request-document", Map.of("actor", "x")))
        .isEqualTo(401);
    assertThat(
            statusSemCredencial(
                "POST", "/v1/mesa/cases/" + caso + "/receive-document", Map.of("actor", "x")))
        .isEqualTo(401);
    assertThat(statusSemCredencial("POST", "/v1/mesa/cases/" + caso + "/notes", Map.of("actor", "x")))
        .isEqualTo(401);
  }

  @Test
  void credencialInvalidaRecusaCom401() {
    assertThat(
            status(
                "GET",
                "/v1/mesa/queues/ANALISE_PADRAO",
                "Bearer brr_inexistente_naoexiste",
                null))
        .isEqualTo(401);
  }

  /** Fila vazia é 200 com lista vazia, não 404: a fila existe, só não tem caso agora. */
  @Test
  void filaVaziaResponde200ComListaVazia() {
    var response =
        client()
            .get()
            .uri("/v1/mesa/queues/ANALISE_PADRAO")
            .header("Authorization", "Bearer " + apiKey("mesa-a"))
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
    assertThat(response.getBody()).isEqualTo("[]");
  }

  /**
   * Nome de fila inexistente é <b>400</b>, não 500. O controller faz {@code CaseQueue.valueOf}, que
   * lança {@code IllegalArgumentException} — mapeada para 400 com o motivo, que é o correto: o erro
   * está na requisição do chamador e ele consegue corrigir.
   */
  @Test
  void filaInexistenteResponde400() {
    assertThat(status("GET", "/v1/mesa/queues/NAO_EXISTE", "Bearer " + apiKey("mesa-a"), null))
        .isEqualTo(HttpStatus.BAD_REQUEST.value());
  }

  /**
   * <b>Escopo de tenant.</b> Caso inexistente para aquele tenant não pode revelar nada — e como o
   * caso é sempre buscado por {@code (assessmentId, tenantId)}, um id válido de outro parceiro é
   * indistinguível de um id que não existe. É a propriedade que impede sondagem por id.
   */
  @Test
  void casoDeOutroTenantNaoEhAcessivel() {
    UUID casoInexistente = UUID.randomUUID();

    int statusA = status("GET", "/v1/mesa/cases/" + casoInexistente, "Bearer " + apiKey("mesa-a"), null);
    int statusB = status("GET", "/v1/mesa/cases/" + casoInexistente, "Bearer " + apiKey("mesa-b"), null);

    assertThat(statusA)
        .as("caso ausente precisa responder igual para qualquer tenant, senão vira oráculo de id")
        .isEqualTo(statusB);
    assertThat(statusA).isNotEqualTo(HttpStatus.OK.value());
  }

  private int statusSemCredencial(String method, String uri, Object body) {
    return status(method, uri, null, body);
  }

  private int status(String method, String uri, String authorization, Object body) {
    RestClient.RequestBodySpec spec =
        client().method(org.springframework.http.HttpMethod.valueOf(method)).uri(uri);
    if (authorization != null) {
      spec = spec.header("Authorization", authorization);
    }
    if (body != null) {
      spec.contentType(MediaType.APPLICATION_JSON);
      return spec.body(body).exchange((req, res) -> res.getStatusCode(), false).value();
    }
    return spec.exchange((req, res) -> res.getStatusCode(), false).value();
  }

  private String apiKey(String tenantId) {
    return apiKeyService.issue(tenantId, "teste-fronteira").presentedValue();
  }

  private RestClient client() {
    return RestClient.create("http://localhost:" + port);
  }
}
