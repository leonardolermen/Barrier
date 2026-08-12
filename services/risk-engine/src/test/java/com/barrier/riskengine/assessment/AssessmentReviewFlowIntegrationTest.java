package com.barrier.riskengine.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.assessment.controller.dto.AssessmentResponse;
import com.barrier.riskengine.assessment.controller.dto.ReviewDecisionRequest;
import com.barrier.riskengine.assessment.controller.dto.SubmitAssessmentRequest;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.service.AssessmentProcessor;
import com.barrier.riskengine.tenant.service.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Fluxo da decisão manual (EDD): um documento com apontamento PEP cai em EM_REVISAO; o tenant
 * dono decide via {@code POST /{id}/decision}, a avaliação vira APROVADO com a trilha de review, e
 * decidir de novo (já fora de revisão) responde 409. Containers próprios → DB isolado do fluxo
 * automático.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class AssessmentReviewFlowIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Value("${local.server.port}")
  int port;

  @Autowired AssessmentProcessor processor;
  @Autowired ApiKeyService apiKeyService;

  private String apiKey;

  /** Emite a credencial do tenant de teste: a migration não semeia chave conhecida. */
  private String apiKey() {
    if (apiKey == null) {
      apiKey = apiKeyService.issue("default", "teste-integracao").presentedValue();
    }
    return apiKey;
  }

  private RestClient client() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultHeader("Authorization", "Bearer " + apiKey())
        .build();
  }

  private String get(String id) {
    return client()
        .get()
        .uri("/v1/assessments/" + id)
        .retrieve()
        .toEntity(AssessmentResponse.class)
        .getBody()
        .status();
  }

  @Test
  void pepCaiEmRevisaoEDecisaoManualAprova() {
    // documento com apontamento PEP (seed) → EDD → EM_REVISAO
    var request =
        new SubmitAssessmentRequest(DocumentType.CPF, "529.982.247-25", "Fulano Pep Exemplo");
    String id =
        client()
            .post()
            .uri("/v1/assessments")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .toEntity(AssessmentResponse.class)
            .getBody()
            .id();

    assertThat(processor.process()).isEqualTo(1);
    assertThat(get(id)).isEqualTo("EM_REVISAO");

    // decisão humana aprova, com trilha
    var decision =
        new ReviewDecisionRequest(
            ReviewDecisionRequest.Decision.APPROVE, "analista@empresa", "EDD concluída");
    ResponseEntity<AssessmentResponse> decided =
        client()
            .post()
            .uri("/v1/assessments/" + id + "/decision")
            .contentType(MediaType.APPLICATION_JSON)
            .body(decision)
            .retrieve()
            .toEntity(AssessmentResponse.class);

    assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(decided.getBody()).isNotNull();
    assertThat(decided.getBody().status()).isEqualTo("APROVADO");
    assertThat(decided.getBody().reviewedBy()).isEqualTo("analista@empresa");
    assertThat(decided.getBody().reviewedAt()).isNotNull();

    // GET reflete e uma segunda decisão (já fora de revisão) responde 409
    assertThat(get(id)).isEqualTo("APROVADO");
    assertThatThrownBy(
            () ->
                client()
                    .post()
                    .uri("/v1/assessments/" + id + "/decision")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(decision)
                    .retrieve()
                    .toBodilessEntity())
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            e ->
                assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void decisaoDeAvaliacaoInexistenteResponde404() {
    var decision =
        new ReviewDecisionRequest(ReviewDecisionRequest.Decision.REJECT, "analista", null);

    assertThatThrownBy(
            () ->
                client()
                    .post()
                    .uri("/v1/assessments/99999999-9999-9999-9999-999999999999/decision")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(decision)
                    .retrieve()
                    .toBodilessEntity())
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            e ->
                assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }
}
