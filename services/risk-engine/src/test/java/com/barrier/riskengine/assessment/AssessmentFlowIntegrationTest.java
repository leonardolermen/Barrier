package com.barrier.riskengine.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.commons.outbox.OutboxRelay;
import com.barrier.commons.outbox.OutboxRepository;
import com.barrier.commons.outbox.OutboxStatus;
import com.barrier.riskengine.assessment.controller.AssessmentResponse;
import com.barrier.riskengine.assessment.controller.SubmitAssessmentRequest;
import com.barrier.riskengine.assessment.domain.DocumentType;
import com.barrier.riskengine.assessment.service.AssessmentProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Fluxo fim-a-fim da Fase 1: POST -> 202 -> processamento -> outbox publicada -> GET reflete
 * a conclusão. Requer Docker (Testcontainers sobe Postgres e Kafka reais).
 *
 * <p>O scheduling automático é praticamente desativado (delays altos) para o teste acionar
 * o processador e o relay de forma determinística.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class AssessmentFlowIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

  @Autowired TestRestTemplate rest;
  @Autowired AssessmentProcessor processor;
  @Autowired OutboxRelay relay;
  @Autowired OutboxRepository outboxRepository;

  @Test
  void submeteProcessaEPublicaEvento() {
    var request = new SubmitAssessmentRequest(DocumentType.CPF, "111.444.777-35", "Fulano de Tal");

    ResponseEntity<AssessmentResponse> created =
        rest.postForEntity("/v1/assessments", request, AssessmentResponse.class);

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(created.getBody()).isNotNull();
    String id = created.getBody().id();
    assertThat(created.getBody().status()).isEqualTo("EM_ANALISE");

    // processa e publica de forma determinística
    assertThat(processor.process()).isEqualTo(1);
    assertThat(relay.publishPending()).isEqualTo(1);

    // GET reflete a conclusão
    ResponseEntity<AssessmentResponse> fetched =
        rest.getForEntity("/v1/assessments/" + id, AssessmentResponse.class);
    assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(fetched.getBody()).isNotNull();
    assertThat(fetched.getBody().status()).isEqualTo("APROVADO");
    assertThat(fetched.getBody().riskLevel()).isEqualTo("BAIXO");

    // evento foi efetivamente publicado (outbox marcada como SENT)
    var sent = outboxRepository.findByStatusOrderByOccurredAtAsc(OutboxStatus.SENT, Limit.of(10));
    assertThat(sent)
        .hasSize(1)
        .allSatisfy(e -> assertThat(e.getType()).isEqualTo("barrier.assessment.completed"));
  }

  @Test
  void documentoInvalidoRetorna400() {
    var request = new SubmitAssessmentRequest(DocumentType.CPF, "00000000000", "Fulano");

    ResponseEntity<String> response =
        rest.postForEntity("/v1/assessments", request, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
