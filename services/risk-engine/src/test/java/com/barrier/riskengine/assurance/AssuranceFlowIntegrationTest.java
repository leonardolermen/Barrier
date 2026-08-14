package com.barrier.riskengine.assurance;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assessment.controller.dto.AssessmentResponse;
import com.barrier.riskengine.assessment.controller.dto.SubmitAssessmentRequest;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.service.AssessmentProcessor;
import com.barrier.riskengine.assurance.controller.dto.AssuranceCheckResponse;
import com.barrier.riskengine.assurance.controller.dto.ConsentRequest;
import com.barrier.riskengine.assurance.controller.dto.SubmitDocumentRequest;
import com.barrier.riskengine.tenant.service.ApiKeyService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Prova o fluxo ponta a ponta da Task 9: uma avaliação para em {@code SOLICITAR_DOCUMENTO}
 * (risco aprovado, cadastro incompleto), o parceiro submete documentoscopia com consentimento, e
 * isso nasce uma <b>segunda</b> avaliação — sem mutar a primeira.
 *
 * <p>É a costura entre as Tasks 1-8: cadastro incompleto (ADR-0012), a submissão de assurance
 * (Task 5), o listener {@code afterCommit}/{@code REQUIRES_NEW} que dispara a reavaliação (Task
 * 4) e a {@code IdentityAssuranceRiskRule} que faz o desfecho da segunda avaliação depender do
 * resultado da verificação (Task 7). Nenhuma dessas peças, isolada, prova que o parceiro
 * atravessa o fluxo de ponta a ponta — só este teste prova.
 *
 * <p>A resposta do {@code POST .../assurance/document} não devolve o id da reavaliação (ver
 * Javadoc de {@code AssuranceCheckResponse}: a reavaliação roda depois que a resposta já foi
 * montada). Por isso a segunda avaliação é localizada por {@code subject_id}+{@code origin} via
 * {@code JdbcTemplate}, fora de qualquer transação — a mesma prova de commit que {@code
 * AssuranceReassessmentCommitIntegrationTest} já faz — e localizada com um laço de polling (sem
 * {@code Thread.sleep}), porque o trigger roda em {@code TransactionSynchronization.afterCommit}
 * depois que o {@code POST} já respondeu.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
      // barrier.verification.required fica no default (true, ligado): é exatamente essa
      // exigência que faz uma PF sem campo verificado cair em SOLICITAR_DOCUMENTO, o estado
      // que este teste precisa alcançar.
    })
@Testcontainers
class AssuranceFlowIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Value("${local.server.port}")
  int port;

  @Autowired AssessmentProcessor processor;
  @Autowired ApiKeyService apiKeyService;
  @Autowired JdbcTemplate jdbc;

  private static final String CPF = "111.444.777-35";
  private static final String CPF_DIGITS = "11144477735";

  private String apiKey;

  private String apiKey() {
    if (apiKey == null) {
      apiKey = apiKeyService.issue("default", "teste-assurance-e2e").presentedValue();
    }
    return apiKey;
  }

  private RestClient client() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultHeader("Authorization", "Bearer " + apiKey())
        .build();
  }

  private AssessmentResponse get(String id) {
    return client()
        .get()
        .uri("/v1/assessments/" + id)
        .retrieve()
        .toEntity(AssessmentResponse.class)
        .getBody();
  }

  @Test
  void documentoscopiaAposSolicitarDocumentoDisparaSegundaAvaliacaoSemMutarAPrimeira() {
    // 1. Uma avaliação de PF, sem cadastro (CMN 4.753) e sem campo verificado, cai em
    // SOLICITAR_DOCUMENTO: risco aprovado, mas cadastro incompleto (ver AssessmentProcessor).
    ResponseEntity<AssessmentResponse> created =
        client()
            .post()
            .uri("/v1/assessments")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new SubmitAssessmentRequest(DocumentType.CPF, CPF, "Fulano de Tal"))
            .retrieve()
            .toEntity(AssessmentResponse.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    String primeiraId = created.getBody().id();

    assertThat(processor.process()).isEqualTo(1);

    AssessmentResponse primeiraAntes = get(primeiraId);
    assertThat(primeiraAntes.status())
        .withFailMessage("fatores: %s", primeiraAntes.factors())
        .isEqualTo("SOLICITAR_DOCUMENTO");
    String decisaoOriginal = primeiraAntes.decision();
    List<String> fatoresOriginais = primeiraAntes.factors();
    Instant completedAtOriginal = primeiraAntes.completedAt();

    UUID subjectId =
        jdbc.queryForObject(
            "SELECT id FROM subjects WHERE document = ?", UUID.class, CPF_DIGITS);

    // 2. O parceiro submete documentoscopia com consentimento. O captureReference com prefixo
    // "fail-" força o StubDocumentVerificationProvider a devolver FAIL (documento adulterado) —
    // um desfecho que a IdentityAssuranceRiskRule tem de refletir na segunda avaliação, então o
    // teste não fica satisfeito com "nasceu uma avaliação", tem de nascer com o desfecho certo.
    String captureReference = "fail-doc-e2e";
    AssuranceCheckResponse assuranceResponse =
        client()
            .post()
            .uri("/v1/subjects/{doc}/assurance/document", CPF_DIGITS)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                new SubmitDocumentRequest(
                    captureReference,
                    "RG",
                    "hash-e2e",
                    new ConsentRequest(
                        "consent-e2e", "verificação de identidade", Instant.now().minusSeconds(30))))
            .retrieve()
            .toEntity(AssuranceCheckResponse.class)
            .getBody();
    assertThat(assuranceResponse.outcome()).isEqualTo("FAIL");
    String providerReference = assuranceResponse.providerReference();

    // 3. A reavaliação nasce por afterCommit/REQUIRES_NEW — pode não existir ainda quando o POST
    // acima respondeu. Poll por condição real (commit em Postgres, fora de transação), sem
    // Thread.sleep.
    String segundaId = aguardaSegundaAvaliacao(subjectId);

    // origin_detail é DOCUMENT@<providerReference> (AssuranceReassessmentTrigger): a trilha diz
    // qual verificação especificamente disparou esta reavaliação.
    String originDetail =
        jdbc.queryForObject(
            "SELECT origin_detail FROM assessments WHERE id = ?", String.class,
            UUID.fromString(segundaId));
    assertThat(originDetail).isEqualTo("DOCUMENT@" + providerReference);

    // A segunda avaliação nasce EM_ANALISE (o scheduling automático está desligado neste teste);
    // processa deterministicamente para o desfecho final.
    assertThat(processor.process()).isEqualTo(1);

    // 5. O desfecho da segunda reflete o resultado da verificação: FAIL empurra a
    // IdentityAssuranceRiskRule para REVIEW (score alto, recomendação REVIEW) -> EM_REVISAO.
    AssessmentResponse segunda = get(segundaId);
    assertThat(segunda.status())
        .withFailMessage("fatores: %s", segunda.factors())
        .isEqualTo("EM_REVISAO");
    assertThat(segunda.factors())
        .anyMatch(f -> f.contains("documentoscopia") || f.contains("Verificação de titularidade"));

    // 4. A primeira avaliação não foi mutada: mesmo status, mesma decisão, mesma trilha de
    // fatores, mesmo completedAt — nada no processamento da segunda pode reescrever a primeira.
    AssessmentResponse primeiraDepois = get(primeiraId);
    assertThat(primeiraDepois.status()).isEqualTo("SOLICITAR_DOCUMENTO");
    assertThat(primeiraDepois.decision()).isEqualTo(decisaoOriginal);
    assertThat(primeiraDepois.factors()).isEqualTo(fatoresOriginais);
    assertThat(primeiraDepois.completedAt()).isEqualTo(completedAtOriginal);
    assertThat(primeiraDepois.id()).isNotEqualTo(segundaId);
  }

  /**
   * Consulta por {@code JdbcTemplate}, fora de qualquer transação do teste, para provar commit —
   * não estado de sessão/1º nível do JPA. Laço de polling com timeout curto no lugar de
   * {@code Thread.sleep}: o projeto ainda não depende de Awaitility.
   */
  private String aguardaSegundaAvaliacao(UUID subjectId) {
    Instant limite = Instant.now().plus(Duration.ofSeconds(10));
    while (Instant.now().isBefore(limite)) {
      List<String> ids =
          jdbc.queryForList(
              "SELECT id FROM assessments WHERE subject_id = ? AND origin = 'ASSURANCE'"
                  + " ORDER BY created_at DESC",
              String.class,
              subjectId);
      if (!ids.isEmpty()) {
        return ids.get(0);
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrompido esperando a reavaliação de assurance", e);
      }
    }
    throw new AssertionError(
        "nenhuma avaliação com origin=ASSURANCE commitada para o subject " + subjectId
            + " em 10s — a reavaliação pode estar sendo descartada pelo pitfall do afterCommit");
  }
}
