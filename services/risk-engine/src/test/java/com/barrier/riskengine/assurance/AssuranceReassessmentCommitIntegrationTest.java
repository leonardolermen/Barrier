package com.barrier.riskengine.assurance;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assurance.client.DocumentSubmission;
import com.barrier.riskengine.assurance.domain.AssuranceConsent;
import com.barrier.riskengine.assurance.service.AssuranceService;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import java.time.Instant;
import java.util.List;
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
 * Prova, contra Postgres real, o caminho feliz que faltava na Task 4: uma verificação de
 * assurance grava, o {@code AssuranceReassessmentTrigger} reage e a reavaliação com
 * {@code origin = ASSURANCE} fica <b>commitada</b> — não só "o método não lançou".
 *
 * <p>É o mesmo pitfall do {@code afterCommit}: {@code AssuranceReassessmentTrigger.onRecorded}
 * roda dentro do {@code TransactionSynchronization} registrado por
 * {@code AssuranceService.scheduleNotification}. No {@code JpaTransactionManager}, o
 * {@code EntityManagerHolder} ainda está ligado à thread durante {@code afterCommit} — a
 * transação "acabou de commitar", mas a sincronização não foi limpa. Um {@code @Transactional}
 * puro (`PROPAGATION_REQUIRED`, o caso de {@code AssessmentService.submit}) <b>entra</b> nessa
 * transação já commitada em vez de abrir uma nova, então o {@code Assessment} da reavaliação, o
 * vínculo tenant↔subject e a linha do outbox não commitam nunca — silenciosamente, sem lançar. Um
 * teste com Mockito não vê isso porque não existe {@code EntityManager}/transação real para
 * poluir; só Postgres com JPA de verdade expõe o efeito.
 */
@SpringBootTest(
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class AssuranceReassessmentCommitIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Autowired AssuranceService service;
  @Autowired SubjectService subjectService;
  @Autowired JdbcTemplate jdbc;

  private AssuranceConsent consentimento() {
    return new AssuranceConsent("ref-1", "EKYC", Instant.now());
  }

  @Test
  void verificacaoDeDocumentoDisparaReavaliacaoCommitada() {
    Subject subject = subjectService.findOrCreate("CPF", "52998224725", "Fulano de Tal");
    UUID subjectId = subject.id();
    // o trigger resolve o subject escopado por tenant — precisa do vínculo, como qualquer POST
    // real garantiria.
    subjectService.link("default", subjectId);

    service.verifyDocument(
        subjectId, "default", new DocumentSubmission("ref", "RG", "hash"), consentimento());

    // consulta nova, fora de qualquer transação desta chamada: só enxerga o que está commitado.
    List<String> origins =
        jdbc.queryForList(
            "SELECT origin FROM assessments WHERE subject_id = ? AND tenant_id = ?"
                + " ORDER BY created_at DESC",
            String.class,
            subjectId,
            "default");

    assertThat(origins)
        .withFailMessage(
            "esperava uma avaliação com origin=ASSURANCE commitada para o subject %s; achei %s"
                + " — se vier vazio, a reavaliação está sendo descartada silenciosamente pelo"
                + " pitfall do afterCommit (participação na transação já commitada em vez de"
                + " REQUIRES_NEW)",
            subjectId,
            origins)
        .contains("ASSURANCE");

    // o vínculo tenant↔subject que AssessmentService.create cria também precisa ter commitado.
    Boolean linked =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tenant_subjects WHERE tenant_id = ? AND subject_id = ?)",
            Boolean.class,
            "default",
            subjectId);
    assertThat(linked).isTrue();
  }
}
