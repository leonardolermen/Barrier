package com.barrier.riskengine.riskstate;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.repository.interfaces.AssessmentRepository;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.riskstate.domain.RiskLevelTransition;
import com.barrier.riskengine.riskstate.domain.SubjectRiskState;
import com.barrier.riskengine.riskstate.service.SubjectRiskStateService;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.repository.interfaces.SubjectRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Prova a projeção contra Postgres real: a migration V041 (chave composta, FKs e o cast do backfill
 * entre {@code risk_scores.assessment_id} VARCHAR e {@code assessments.id} UUID) e o upsert que
 * substitui a linha em vez de duplicá-la.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class SubjectRiskStateIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired SubjectRiskStateService service;
  @Autowired SubjectRepository subjects;
  @Autowired AssessmentRepository assessments;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;

  @Test
  void projeta_o_desfecho_e_substitui_a_linha_do_mesmo_par_subject_tenant() {
    Subject subject = criarSubject("11144477735", "Fulano de Tal");

    Assessment primeira = concluida(subject, RiskLevel.LOW, AssessmentStatus.APROVADO);
    assertThat(service.record(primeira, 40, "motor/1.0")).isEmpty();

    Assessment segunda = concluida(subject, RiskLevel.CRITICAL, AssessmentStatus.REPROVADO);
    Optional<RiskLevelTransition> transicao = service.record(segunda, 900, "motor/1.0");

    assertThat(transicao).contains(new RiskLevelTransition(RiskLevel.LOW, RiskLevel.CRITICAL));
    SubjectRiskState corrente = service.find(subject.id(), "default").orElseThrow();
    assertThat(corrente.level()).isEqualTo(RiskLevel.CRITICAL);
    assertThat(corrente.score()).isEqualTo(900);
    assertThat(corrente.assessmentId()).isEqualTo(segunda.id().value());
  }

  /** A projeção é por tenant: o mesmo cliente pode estar bom num parceiro e ruim em outro. */
  @Test
  void tenants_diferentes_tem_estados_independentes_para_o_mesmo_subject() {
    Subject subject = criarSubject("52998224725", "Beltrano");

    service.record(concluida(subject, RiskLevel.HIGH, AssessmentStatus.EM_REVISAO), 600, "motor/1.0");

    assertThat(service.find(subject.id(), "default")).isPresent();
    assertThat(service.find(subject.id(), "outro-tenant")).isEmpty();
  }

  private Subject criarSubject(String documento, String nome) {
    return tx.execute(
        status -> {
          Subject subject = subjects.save(Subject.create("CPF", documento, nome));
          subjects.link("default", subject.id());
          return subject;
        });
  }

  private Assessment concluida(Subject subject, RiskLevel level, AssessmentStatus estado) {
    Assessment assessment =
        Assessment.submit(
            "default", subject.id().toString(), DocumentType.CPF, subject.document(), subject.name());
    return tx.execute(
        status -> {
          Assessment salva = assessments.save(assessment);
          salva.complete(level, estado, "decisão do motor", List.of());
          return assessments.save(salva);
        });
  }
}
