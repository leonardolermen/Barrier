package com.barrier.riskengine.assurance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.barrier.riskengine.assurance.client.BiometricSubmission;
import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceConsent;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.assurance.domain.DocumentGateNotSatisfiedException;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import com.barrier.riskengine.assurance.service.AssuranceService;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Prova, contra Postgres real, que o gate de documentoscopia não cruza entre tenants — mesmo
 * padrão de {@code AssessmentRepositoryIntegrationTest#naoCruzaEntreTenantsDoMesmoSubjectGlobal}.
 *
 * <p>Documentoscopia {@code PASS} do tenant A não pode liberar biometria do tenant B para o
 * <b>mesmo subject global</b> (ADR-0011: um documento, um {@code Subject}, N tenants vinculados).
 * Critério de pronto: apague a checagem de {@code tenantId} em
 * {@code AssuranceService#requireDocumentPass} (troque a busca por {@code (subjectId, tenantId,
 * DOCUMENT)} por só {@code subjectId}) e este teste fica vermelho.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class DocumentGateCrossTenantIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired AssuranceService assuranceService;
  @Autowired AssuranceCheckRepository repository;
  @Autowired SubjectService subjectService;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean BiometricVerificationProvider biometricProvider;

  private static final String TENANT_A = "tenant-gate-a";
  private static final String TENANT_B = "tenant-gate-b";

  private void garanteTenant(String tenantId) {
    jdbc.update(
        "INSERT INTO tenants (id, name, active) VALUES (?, ?, true) ON CONFLICT (id) DO NOTHING",
        tenantId,
        tenantId);
  }

  private AssuranceConsent consentimento() {
    return new AssuranceConsent("consent-cross-tenant", "verificação de identidade", Instant.now());
  }

  private AssuranceCheck documentoAprovado(UUID subjectId, String tenantId) {
    return new AssuranceCheck(
        UUID.randomUUID(),
        subjectId,
        tenantId,
        AssuranceKind.DOCUMENT,
        AssuranceOutcome.PASS,
        95,
        "provedor-teste",
        "ref-cross-tenant",
        "v1",
        "hash",
        "aprovado",
        Set.of(),
        Instant.now(),
        null);
  }

  @Test
  void documentoscopiaAprovadaDeUmTenantNaoLiberaBiometriaDeOutroTenantParaOMesmoSubjectGlobal() {
    garanteTenant(TENANT_A);
    garanteTenant(TENANT_B);
    Subject subject = subjectService.findOrCreate("CPF", "10077766650", "Fulano de Tal");
    subjectService.link(TENANT_A, subject.id());
    subjectService.link(TENANT_B, subject.id());

    // Documentoscopia aprovada só existe para o tenant A.
    repository.save(documentoAprovado(subject.id(), TENANT_A));

    assertThat(repository.findLatest(subject.id(), TENANT_A, AssuranceKind.DOCUMENT))
        .as("sanity check: a documentoscopia de A está mesmo lá")
        .isPresent();

    // O tenant B, vinculado ao MESMO subject global, não herda a documentoscopia de A.
    assertThatThrownBy(
            () ->
                assuranceService.verifyBiometrics(
                    subject.id(),
                    TENANT_B,
                    new BiometricSubmission("selfie-b", "face-b", "hash-b"),
                    consentimento()))
        .isInstanceOf(DocumentGateNotSatisfiedException.class);

    verifyNoInteractions(biometricProvider);
  }
}
