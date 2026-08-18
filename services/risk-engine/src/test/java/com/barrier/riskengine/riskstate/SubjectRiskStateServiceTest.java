package com.barrier.riskengine.riskstate;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.riskstate.domain.RiskLevelTransition;
import com.barrier.riskengine.riskstate.domain.SubjectRiskState;
import com.barrier.riskengine.riskstate.repository.interfaces.SubjectRiskStateRepository;
import com.barrier.riskengine.riskstate.service.SubjectRiskStateService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A projeção de risco corrente e, sobretudo, a ordenação que a protege: ela é monotônica no tempo
 * da <b>avaliação</b>, não no tempo da gravação.
 */
class SubjectRiskStateServiceTest {

  private static final String TENANT = "acme";
  private static final String SUBJECT = UUID.randomUUID().toString();

  private final InMemoryRepository repository = new InMemoryRepository();
  private final SubjectRiskStateService service = new SubjectRiskStateService(repository);

  @Test
  void primeira_avaliacao_cria_o_estado_e_nao_reporta_transicao() {
    Optional<RiskLevelTransition> transition =
        service.record(concluida(RiskLevel.LOW, AssessmentStatus.APROVADO, Instant.now()), 50, "v1");

    assertThat(transition).isEmpty();
    assertThat(service.find(UUID.fromString(SUBJECT), TENANT))
        .get()
        .extracting(SubjectRiskState::level)
        .isEqualTo(RiskLevel.LOW);
  }

  @Test
  void avaliacao_mais_nova_sobrescreve_e_reporta_a_transicao() {
    Instant ontem = Instant.now().minus(1, ChronoUnit.DAYS);
    service.record(concluida(RiskLevel.LOW, AssessmentStatus.APROVADO, ontem), 50, "v1");

    Optional<RiskLevelTransition> transition =
        service.record(
            concluida(RiskLevel.HIGH, AssessmentStatus.EM_REVISAO, Instant.now()), 700, "v1");

    assertThat(transition).contains(new RiskLevelTransition(RiskLevel.LOW, RiskLevel.HIGH));
    assertThat(transition).get().extracting(RiskLevelTransition::worsened).isEqualTo(true);
    assertThat(service.find(UUID.fromString(SUBJECT), TENANT))
        .get()
        .extracting(SubjectRiskState::level)
        .isEqualTo(RiskLevel.HIGH);
  }

  @Test
  void mesmo_nivel_atualiza_o_estado_mas_nao_reporta_transicao() {
    service.record(
        concluida(RiskLevel.MEDIUM, AssessmentStatus.APROVADO, Instant.now().minusSeconds(60)),
        300,
        "v1");

    Optional<RiskLevelTransition> transition =
        service.record(
            concluida(RiskLevel.MEDIUM, AssessmentStatus.APROVADO, Instant.now()), 320, "v1");

    assertThat(transition).isEmpty();
    assertThat(service.find(UUID.fromString(SUBJECT), TENANT))
        .get()
        .extracting(SubjectRiskState::score)
        .isEqualTo(320);
  }

  /**
   * O caso que motiva o campo {@code evaluatedAt}: rescreening e reavaliação por assurance rodam
   * concorrentes, e a que começou antes pode commitar depois. Se a projeção seguisse a ordem de
   * gravação, o risco corrente andaria para trás sem que nada acusasse.
   */
  @Test
  void avaliacao_concluida_fora_de_ordem_nao_sobrescreve_estado_mais_novo() {
    service.record(
        concluida(RiskLevel.CRITICAL, AssessmentStatus.REPROVADO, Instant.now()), 950, "v1");

    Optional<RiskLevelTransition> transition =
        service.record(
            concluida(
                RiskLevel.LOW, AssessmentStatus.APROVADO, Instant.now().minus(1, ChronoUnit.HOURS)),
            10,
            "v1");

    assertThat(transition).isEmpty();
    assertThat(service.find(UUID.fromString(SUBJECT), TENANT))
        .get()
        .extracting(SubjectRiskState::level)
        .isEqualTo(RiskLevel.CRITICAL);
  }

  @Test
  void avaliacao_sem_subject_nao_projeta_nada() {
    Assessment sem =
        Assessment.rehydrate(
            com.barrier.riskengine.assessment.domain.assessment.AssessmentId.newId(),
            TENANT,
            null,
            DocumentType.CPF,
            "52998224725",
            "Fulano",
            AssessmentStatus.APROVADO,
            RiskLevel.LOW,
            "ok",
            List.of(),
            Instant.now(),
            Instant.now(),
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            0,
            null,
            null,
            null);

    assertThat(service.record(sem, 10, "v1")).isEmpty();
    assertThat(service.find(UUID.fromString(SUBJECT), TENANT)).isEmpty();
  }

  private Assessment concluida(RiskLevel level, AssessmentStatus status, Instant completedAt) {
    return Assessment.rehydrate(
        com.barrier.riskengine.assessment.domain.assessment.AssessmentId.newId(),
        TENANT,
        SUBJECT,
        DocumentType.CPF,
        "52998224725",
        "Fulano de Tal",
        status,
        level,
        "decisão",
        List.of(),
        completedAt.minusSeconds(5),
        completedAt,
        null,
        null,
        null,
        null,
        0,
        null,
        null,
        0,
        null,
        null,
        null);
  }

  /** Dobra do repositório: a chave composta vira a chave do mapa. */
  private static final class InMemoryRepository implements SubjectRiskStateRepository {

    private final Map<String, SubjectRiskState> states = new HashMap<>();

    @Override
    public Optional<SubjectRiskState> find(UUID subjectId, String tenantId) {
      return Optional.ofNullable(states.get(subjectId + "|" + tenantId));
    }

    @Override
    public SubjectRiskState save(SubjectRiskState state) {
      states.put(state.subjectId() + "|" + state.tenantId(), state);
      return state;
    }

    @Override
    public List<SubjectRiskState> findDueForPeriodicReview(
        java.time.Duration menorIntervalo, int limit) {
      java.time.Instant corte = Instant.now().minus(menorIntervalo);
      return states.values().stream()
          .filter(s -> s.evaluatedAt().isBefore(corte))
          .sorted(java.util.Comparator.comparing(SubjectRiskState::evaluatedAt))
          .limit(limit)
          .toList();
    }
  }
}
