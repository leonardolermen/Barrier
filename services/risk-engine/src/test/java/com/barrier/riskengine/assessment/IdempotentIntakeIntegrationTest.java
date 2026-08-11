package com.barrier.riskengine.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.assessment.controller.AssessmentResponse;
import com.barrier.riskengine.assessment.controller.SubmitAssessmentRequest;
import com.barrier.riskengine.assessment.domain.DocumentType;
import com.barrier.riskengine.tenant.service.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Idempotência do intake contra Postgres real: o retry do cliente não pode virar uma segunda
 * avaliação. Duas avaliações do mesmo cliente feitas em momentos diferentes podem decidir
 * diferente, e é isso que transforma o retry em oráculo.
 *
 * <p>O processamento assíncrono fica praticamente desligado (delays altos) — o que está sob teste é
 * o intake, não a decisão.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class IdempotentIntakeIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Value("${local.server.port}")
  int port;

  @Autowired ApiKeyService apiKeyService;
  @Autowired JdbcTemplate jdbc;

  private String apiKey;

  private String apiKey() {
    if (apiKey == null) {
      apiKey = apiKeyService.issue("default", "teste-idempotencia").presentedValue();
    }
    return apiKey;
  }

  private RestClient client() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultHeader("Authorization", "Bearer " + apiKey())
        .build();
  }

  private ResponseEntity<AssessmentResponse> submit(
      String key, DocumentType type, String document, String name) {
    RestClient.RequestBodySpec spec =
        client().post().uri("/v1/assessments").contentType(MediaType.APPLICATION_JSON);
    if (key != null) {
      spec = spec.header("Idempotency-Key", key);
    }
    return spec.body(new SubmitAssessmentRequest(type, document, name))
        .retrieve()
        .toEntity(AssessmentResponse.class);
  }

  private long avaliacoesDe(String documento) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM assessments WHERE document_value = ?", Long.class, documento);
    return count == null ? 0 : count;
  }

  @Test
  void mesmaChaveComMesmoConteudoDevolveAAvaliacaoOriginal() {
    ResponseEntity<AssessmentResponse> primeira =
        submit("retry-1", DocumentType.CPF, "100.200.300-88", "Beltrana");
    ResponseEntity<AssessmentResponse> segunda =
        submit("retry-1", DocumentType.CPF, "100.200.300-88", "Beltrana");

    assertThat(primeira.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(segunda.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(segunda.getBody()).isNotNull();
    assertThat(segunda.getBody().id()).isEqualTo(primeira.getBody().id());
    assertThat(primeira.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
    assertThat(segunda.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    assertThat(avaliacoesDe("10020030088")).isEqualTo(1);
  }

  /** O documento escrito com e sem máscara é a mesma requisição — não pode virar conflito. */
  @Test
  void mesmaChaveComDocumentoFormatadoDiferenteAindaERepeticao() {
    ResponseEntity<AssessmentResponse> primeira =
        submit("retry-2", DocumentType.CPF, "210.320.430-16", "Ciclana");
    ResponseEntity<AssessmentResponse> segunda =
        submit("retry-2", DocumentType.CPF, "21032043016", "Ciclana");

    assertThat(segunda.getBody().id()).isEqualTo(primeira.getBody().id());
    assertThat(avaliacoesDe("21032043016")).isEqualTo(1);
  }

  @Test
  void mesmaChaveComOutroConteudoResponde409() {
    submit("retry-3", DocumentType.CPF, "320.430.540-66", "Fulano");

    assertThatThrownBy(() -> submit("retry-3", DocumentType.CPF, "430.540.650-06", "Outro"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            e ->
                assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    assertThat(avaliacoesDe("43054065006")).isZero();
  }

  @Test
  void chavesDiferentesCriamAvaliacoesDiferentes() {
    String id1 = submit("a", DocumentType.CPF, "540.650.760-56", "Beltrana").getBody().id();
    String id2 = submit("b", DocumentType.CPF, "540.650.760-56", "Beltrana").getBody().id();

    assertThat(id1).isNotEqualTo(id2);
  }

  /** Sem chave, o comportamento é o de antes: cada POST cria uma avaliação. */
  @Test
  void semChaveCadaPostCriaUmaAvaliacao() {
    String id1 = submit(null, DocumentType.CPF, "987.654.321-00", "Sem Chave").getBody().id();
    String id2 = submit(null, DocumentType.CPF, "987.654.321-00", "Sem Chave").getBody().id();

    assertThat(id1).isNotEqualTo(id2);
    assertThat(avaliacoesDe("98765432100")).isEqualTo(2);
  }

  /**
   * Requisição inválida não pode queimar a chave: o cliente corrige o corpo e reenvia com a mesma
   * chave, que é exatamente o que uma biblioteca de retry faz.
   */
  @Test
  void requisicaoInvalidaNaoConsomeAChave() {
    assertThatThrownBy(() -> submit("retry-4", DocumentType.CPF, "00000000000", "Fulano"))
        .isInstanceOf(HttpClientErrorException.class);

    ResponseEntity<AssessmentResponse> corrigida =
        submit("retry-4", DocumentType.CPF, "111.444.777-35", "Fulano");

    assertThat(corrigida.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(corrigida.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
  }

  /** A chave é escopada por tenant: a de um cliente nunca colide com a de outro. */
  @Test
  void chaveIgualDeOutroTenantNaoColide() {
    submit("compartilhada", DocumentType.CPF, "529.982.247-25", "Fulano");

    jdbc.update("INSERT INTO tenants (id, name) VALUES ('outra-empresa', 'Outra') "
        + "ON CONFLICT (id) DO NOTHING");
    String outraChave = apiKeyService.issue("outra-empresa", "teste").presentedValue();
    ResponseEntity<AssessmentResponse> daOutra =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultHeader("Authorization", "Bearer " + outraChave)
            .build()
            .post()
            .uri("/v1/assessments")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "compartilhada")
            .body(new SubmitAssessmentRequest(DocumentType.CPF, "529.982.247-25", "Fulano"))
            .retrieve()
            .toEntity(AssessmentResponse.class);

    assertThat(daOutra.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(daOutra.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
  }
}
