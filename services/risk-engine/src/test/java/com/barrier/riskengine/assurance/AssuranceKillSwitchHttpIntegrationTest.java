package com.barrier.riskengine.assurance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.client.interfaces.DocumentVerificationProvider;
import com.barrier.riskengine.assurance.controller.dto.ConsentRequest;
import com.barrier.riskengine.assurance.controller.dto.SubmitDocumentRequest;
import com.barrier.riskengine.subject.service.SubjectService;
import com.barrier.riskengine.tenant.service.ApiKeyService;
import java.time.Instant;
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
 * Prova pela camada HTTP o kill switch de {@code barrier.assurance.enabled} — até aqui só tinha
 * teste unitário de {@code AssuranceServiceTest}. Convenção registrada em
 * {@code plano-remediacao-auditoria.md}: controle de borda precisa de teste pela camada real, não
 * só pelo service chamado direto.
 *
 * <p>Desligado, {@code AssuranceService.requireEnabled} lança {@code IllegalStateException}, que
 * {@code ProblemExceptionHandler} converte em <b>409</b> — recusa deliberada de operação, não erro
 * de servidor. O ponto central deste teste não é só o status: é provar que o provedor de
 * documentoscopia <b>nunca é acionado</b> quando o kill switch está desligado — o guard roda antes
 * de qualquer chamada de rede, possivelmente paga.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000",
      "barrier.assurance.enabled=false"
    })
@Testcontainers
class AssuranceKillSwitchHttpIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Value("${local.server.port}")
  int port;

  @Autowired ApiKeyService apiKeyService;
  @Autowired SubjectService subjectService;

  @MockitoBean DocumentVerificationProvider documentProvider;
  @MockitoBean BiometricVerificationProvider biometricProvider;

  private static final String CPF_DIGITS = "11144477735";

  private String apiKey;

  private String apiKey() {
    if (apiKey == null) {
      apiKey = apiKeyService.issue("default", "teste-assurance-kill-switch").presentedValue();
    }
    return apiKey;
  }

  private RestClient client() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultHeader("Authorization", "Bearer " + apiKey())
        .build();
  }

  @Test
  void submissaoDeDocumentoscopiaRecusaCom409QuandoOKillSwitchEstaDesligadoENaoAcionaOProvedor() {
    subjectService.link("default", subjectService.findOrCreate("CPF", CPF_DIGITS, "Fulano de Tal").id());

    SubmitDocumentRequest request =
        new SubmitDocumentRequest(
            "ref-kill-switch",
            "RG",
            "hash-kill-switch",
            new ConsentRequest(
                "consent-kill-switch", "verificação de identidade", Instant.now().minusSeconds(30)));

    HttpClientErrorException exception =
        org.junit.jupiter.api.Assertions.assertThrows(
            HttpClientErrorException.class,
            () ->
                client()
                    .post()
                    .uri("/v1/subjects/{doc}/assurance/document", CPF_DIGITS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity());

    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    verifyNoInteractions(documentProvider, biometricProvider);
  }
}
