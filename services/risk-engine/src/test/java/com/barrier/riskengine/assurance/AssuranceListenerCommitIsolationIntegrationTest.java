package com.barrier.riskengine.assurance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assurance.client.DocumentSubmission;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceConsent;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.service.AssuranceRecordedListener;
import com.barrier.riskengine.assurance.service.AssuranceService;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Prova, contra Postgres real, que uma falha de banco dentro de um listener não perde a
 * verificação de assurance que acabou de ser gravada.
 *
 * <p>É o cenário que um teste com Mockito não enxerga: lá não existe transação de verdade, então
 * {@code TransactionSynchronizationManager.isSynchronizationActive()} é sempre falso e o
 * listener é chamado na hora — o que prova o isolamento por {@code try/catch}, mas não prova que
 * o notify roda <b>depois do commit</b>. Antes desta correção, {@code notifyListeners} rodava
 * dentro da mesma transação de {@code verifyDocument}; um erro real de banco dentro do listener
 * (violação de índice único, aqui simulada tentando inserir a mesma linha duas vezes) marcaria
 * essa transação como rollback-only, e o commit final estouraria
 * {@code UnexpectedRollbackException} — perdendo o {@code AssuranceCheck} que já tinha sido
 * gravado, com um log dizendo "a gravação segue válida". Só um Postgres de verdade demonstra
 * commit/rollback; um mock de repositório não tem transação nenhuma para poluir.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class AssuranceListenerCommitIsolationIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @TestConfiguration
  static class FalhaDeBancoNoListenerConfig {

    /**
     * Simula a corrida tratada em {@code SubjectService.create}: uma violação de índice único
     * real, disparada pelo próprio listener. Tenta inserir a mesma verificação outra vez — o
     * mesmo {@code id}, chave primária de {@code identity_assurance_checks} — e captura só o que
     * o {@code AssuranceService} não deveria precisar capturar.
     */
    @Bean
    AssuranceRecordedListener listenerQueViolaConstraintUnica(JdbcTemplate jdbc) {
      return check -> {
        jdbc.update(
            "INSERT INTO identity_assurance_checks"
                + " (id, subject_id, tenant_id, kind, outcome, score, provider,"
                + " provider_reference, algorithm_version, submitted_hash, detail, checked_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            check.id(),
            check.subjectId(),
            check.tenantId(),
            check.kind().name(),
            check.outcome().name(),
            check.score(),
            "duplicado-de-proposito",
            check.providerReference(),
            check.algorithmVersion(),
            check.submittedHash(),
            "linha duplicada de propósito para provar isolamento pós-commit",
            java.sql.Timestamp.from(check.checkedAt()));
      };
    }
  }

  @Autowired AssuranceService service;
  @Autowired SubjectService subjectService;
  @Autowired JdbcTemplate jdbc;

  private AssuranceConsent consentimento() {
    return new AssuranceConsent("ref-1", "EKYC", Instant.now());
  }

  @Test
  void falhaRealDeBancoNoListenerNaoDerrubaAVerificacaoJaComitada() {
    // identity_assurance_checks.subject_id tem FK para subjects — precisa existir de verdade.
    Subject subject = subjectService.findOrCreate("CPF", "52998224725", "Fulano de Tal");
    UUID subjectId = subject.id();
    // Em produção o controller (AssuranceController) só chega a chamar verifyDocument depois de
    // resolver o subject com vínculo exigido (SubjectService.getForTenant) — aqui o teste chama o
    // service direto, então precisa criar o mesmo vínculo à mão para reproduzir o caminho real:
    // reconcileWithCadastro agora lê o Subject via SubjectService.findById, que também exige
    // vínculo (mesma defesa, um nível abaixo).
    subjectService.link("default", subjectId);

    assertThatCode(
            () ->
                service.verifyDocument(
                    subjectId,
                    "default",
                    new DocumentSubmission("ref", "RG", "hash"),
                    consentimento()))
        .doesNotThrowAnyException();

    // a verificação está commitada e legível numa consulta nova, fora de qualquer transação desta
    // chamada — não é só "o método não lançou", é "a linha está lá".
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM identity_assurance_checks WHERE subject_id = ? AND provider <> ?",
            Integer.class,
            subjectId,
            "duplicado-de-proposito");
    assertThat(count).isEqualTo(1);
  }

  /**
   * Sanidade complementar: a violação de índice único que o listener provoca é, de fato, um erro
   * real de banco — não um mock que finge falhar. Se o Postgres aceitasse a segunda linha com o
   * mesmo id, este teste não provaria nada.
   */
  @Test
  void aInsercaoDuplicadaEDeVerdadeUmaViolacaoDeConstraint() {
    Subject subject = subjectService.findOrCreate("CPF", "11144477735", "Beltrano de Tal");
    AssuranceCheck check =
        new AssuranceCheck(
            UUID.randomUUID(),
            subject.id(),
            "default",
            AssuranceKind.DOCUMENT,
            com.barrier.riskengine.assurance.domain.AssuranceOutcome.PASS,
            90,
            "p",
            "ref",
            "v1",
            "hash",
            "d",
            java.util.Set.of(),
            Instant.now(),
            null);
    jdbc.update(
        "INSERT INTO identity_assurance_checks"
            + " (id, subject_id, tenant_id, kind, outcome, score, provider, provider_reference,"
            + " algorithm_version, submitted_hash, detail, checked_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        check.id(),
        check.subjectId(),
        check.tenantId(),
        check.kind().name(),
        check.outcome().name(),
        check.score(),
        check.provider(),
        check.providerReference(),
        check.algorithmVersion(),
        check.submittedHash(),
        check.detail(),
        java.sql.Timestamp.from(check.checkedAt()));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO identity_assurance_checks"
                        + " (id, subject_id, tenant_id, kind, outcome, score, provider,"
                        + " provider_reference, algorithm_version, submitted_hash, detail,"
                        + " checked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    check.id(),
                    check.subjectId(),
                    check.tenantId(),
                    check.kind().name(),
                    check.outcome().name(),
                    check.score(),
                    check.provider(),
                    check.providerReference(),
                    check.algorithmVersion(),
                    check.submittedHash(),
                    check.detail(),
                    java.sql.Timestamp.from(check.checkedAt())))
        .isInstanceOf(DataAccessException.class);
  }
}
