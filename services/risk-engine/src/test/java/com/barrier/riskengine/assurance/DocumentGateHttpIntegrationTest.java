package com.barrier.riskengine.assurance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.controller.dto.AssuranceCheckResponse;
import com.barrier.riskengine.assurance.controller.dto.ConsentRequest;
import com.barrier.riskengine.assurance.controller.dto.SubmitBiometricRequest;
import com.barrier.riskengine.assurance.controller.dto.SubmitDocumentRequest;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.subject.service.SubjectService;
import com.barrier.riskengine.tenant.service.ApiKeyService;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Prova pela camada HTTP que documentoscopia aprovada é pré-requisito da biometria — decisão de
 * produto de 2026-08-13. Convenção do {@code plano-remediacao-auditoria.md}: controle de borda
 * precisa de teste pela camada real, não só pelo service chamado direto (ver também
 * {@code AssuranceKillSwitchHttpIntegrationTest}, mesmo padrão para o kill switch).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class DocumentGateHttpIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Value("${local.server.port}")
  int port;

  @Autowired ApiKeyService apiKeyService;
  @Autowired SubjectService subjectService;

  @MockitoBean BiometricVerificationProvider biometricProvider;

  private String apiKey;

  private String apiKey() {
    if (apiKey == null) {
      apiKey = apiKeyService.issue("default", "teste-document-gate").presentedValue();
    }
    return apiKey;
  }

  private RestClient client() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultHeader("Authorization", "Bearer " + apiKey())
        .build();
  }

  private SubmitBiometricRequest pedidoBiometrico(String selfieReference) {
    return new SubmitBiometricRequest(
        selfieReference,
        "face-ref",
        "hash-gate",
        new ConsentRequest(
            "consent-gate", "verificação de identidade", Instant.now().minusSeconds(30)));
  }

  @Test
  void biometriaSemDocumentoscopiaAlgumaRecusaCom409ENaoAcionaOProvedor() {
    String cpf = "10088877764";
    subjectService.link("default", subjectService.findOrCreate("CPF", cpf, "Fulano de Tal").id());

    HttpClientErrorException exception =
        org.junit.jupiter.api.Assertions.assertThrows(
            HttpClientErrorException.class,
            () ->
                client()
                    .post()
                    .uri("/v1/subjects/{doc}/assurance/biometric", cpf)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(pedidoBiometrico("selfie-sem-doc"))
                    .retrieve()
                    .toBodilessEntity());

    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    verifyNoInteractions(biometricProvider);
  }

  @Test
  void biometriaComDocumentoscopiaAprovadaProcessaNormalmente() {
    String cpf = "10099988872";
    subjectService.link("default", subjectService.findOrCreate("CPF", cpf, "Fulano de Tal").id());

    // documentoscopia aprovada: StubDocumentVerificationProvider aprova qualquer referência sem
    // os prefixos fail-/inconclusive-/unavailable-.
    client()
        .post()
        .uri("/v1/subjects/{doc}/assurance/document", cpf)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new SubmitDocumentRequest(
                "ref-doc-gate-ok",
                "RG",
                "hash-doc-gate",
                new ConsentRequest(
                    "consent-doc-gate",
                    "verificação de identidade",
                    Instant.now().minusSeconds(30))))
        .retrieve()
        .toBodilessEntity();

    when(biometricProvider.requestVerification(any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                new AssuranceCheck(
                    UUID.randomUUID(),
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    AssuranceKind.BIOMETRIC,
                    AssuranceOutcome.PASS,
                    97,
                    "mock",
                    "mock-ref",
                    "v1",
                    "hash-gate",
                    "ok",
                    Set.of(),
                    Instant.now(),
                    null));

    AssuranceCheckResponse response =
        client()
            .post()
            .uri("/v1/subjects/{doc}/assurance/biometric", cpf)
            .contentType(MediaType.APPLICATION_JSON)
            .body(pedidoBiometrico("selfie-com-doc-ok"))
            .retrieve()
            .toEntity(AssuranceCheckResponse.class)
            .getBody();

    assertThat(response.outcome()).isEqualTo("PASS");
  }
}
