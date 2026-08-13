package com.barrier.riskengine.assessment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentOrigin;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.repository.interfaces.AssessmentRepository;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.assessment.service.SubmitAssessmentCommand;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Prova contra Postgres real o SQL de {@code existsRecentByOriginAndSubject} — base do dedup de
 * reavaliação do {@code AssuranceReassessmentTrigger} (ver Javadoc dele e de
 * {@code AssessmentService.existsRecentByOriginAndSubject}).
 *
 * <p>O ponto central é o isolamento por tenant: {@code subjects} é global (ADR-0011), então dois
 * parceiros podem ter avaliação {@code ASSURANCE} recente para o <b>mesmo</b> {@code subject_id}
 * sem que uma enxergue a outra. Convenção do {@code plano-remediacao-auditoria.md} — apague o
 * {@code AND tenant_id = ?} da query em {@code AssessmentRepositoryImpl} e
 * {@link #naoCruzaEntreTenantsDoMesmoSubjectGlobal} fica vermelho.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class AssessmentRepositoryIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired AssessmentRepository repository;
  @Autowired AssessmentService assessmentService;
  @Autowired JdbcTemplate jdbc;

  private static final Duration WINDOW = Duration.ofMinutes(5);

  private void garanteTenant(String tenantId) {
    jdbc.update(
        "INSERT INTO tenants (id, name, active) VALUES (?, ?, true) ON CONFLICT (id) DO NOTHING",
        tenantId,
        tenantId);
  }

  private UUID submeteEDescobreSubject(String tenantId, String cpf, AssessmentOrigin origin) {
    var comando =
        origin == AssessmentOrigin.ASSURANCE
            ? SubmitAssessmentCommand.assurance(
                tenantId, DocumentType.CPF, cpf, "Fulano de Tal", "DOCUMENT@ref")
            : new SubmitAssessmentCommand(tenantId, DocumentType.CPF, cpf, "Fulano de Tal");
    assessmentService.submit(comando);
    return jdbc.queryForObject("SELECT id FROM subjects WHERE document = ?", UUID.class, cpf);
  }

  @Test
  void encontraAvaliacaoAssuranceRecenteParaOMesmoSubjectETenant() {
    garanteTenant("tenant-repo-a");
    UUID subjectId =
        submeteEDescobreSubject("tenant-repo-a", "10011111178", AssessmentOrigin.ASSURANCE);

    boolean existe =
        repository.existsRecentByOriginAndSubject(
            subjectId, "tenant-repo-a", AssessmentOrigin.ASSURANCE, WINDOW);

    assertThat(existe).isTrue();
  }

  @Test
  void naoEncontraQuandoAOrigemENaoAssurance() {
    garanteTenant("tenant-repo-b");
    UUID subjectId =
        submeteEDescobreSubject("tenant-repo-b", "10022222227", AssessmentOrigin.ONBOARDING);

    boolean existe =
        repository.existsRecentByOriginAndSubject(
            subjectId, "tenant-repo-b", AssessmentOrigin.ASSURANCE, WINDOW);

    assertThat(existe).isFalse();
  }

  @Test
  void naoEncontraForaDaJanela() {
    garanteTenant("tenant-repo-c");
    UUID subjectId =
        submeteEDescobreSubject("tenant-repo-c", "10033333386", AssessmentOrigin.ASSURANCE);

    boolean existe =
        repository.existsRecentByOriginAndSubject(
            subjectId, "tenant-repo-c", AssessmentOrigin.ASSURANCE, Duration.ZERO);

    assertThat(existe).isFalse();
  }

  /**
   * O caso que este teste existe para proteger: mesmo {@code subject_id} (subject global,
   * ADR-0011), dois tenants distintos. Avaliação ASSURANCE recente do tenant A não pode fazer o
   * tenant B ver dedup — seria supressão da reavaliação de B por causa de um evento que B nunca
   * viu, o mesmo tipo de vazamento de efeito entre parceiros que a V024 fechou para dado de
   * cadastro.
   */
  @Test
  void naoCruzaEntreTenantsDoMesmoSubjectGlobal() {
    garanteTenant("tenant-repo-d1");
    garanteTenant("tenant-repo-d2");
    String cpf = "10044444435";

    // tenant-repo-d1 tem avaliação ASSURANCE recente para o subject.
    submeteEDescobreSubject("tenant-repo-d1", cpf, AssessmentOrigin.ASSURANCE);
    // tenant-repo-d2 vincula ao MESMO subject global (mesmo documento), mas nunca submeteu
    // assurance nenhuma.
    UUID subjectId = submeteEDescobreSubject("tenant-repo-d2", cpf, AssessmentOrigin.ONBOARDING);

    boolean tenantD1Ve =
        repository.existsRecentByOriginAndSubject(
            subjectId, "tenant-repo-d1", AssessmentOrigin.ASSURANCE, WINDOW);
    boolean tenantD2Ve =
        repository.existsRecentByOriginAndSubject(
            subjectId, "tenant-repo-d2", AssessmentOrigin.ASSURANCE, WINDOW);

    assertThat(tenantD1Ve).isTrue();
    assertThat(tenantD2Ve)
        .as("tenant-repo-d2 não pode herdar o dedup da avaliação ASSURANCE de outro tenant")
        .isFalse();
  }
}
