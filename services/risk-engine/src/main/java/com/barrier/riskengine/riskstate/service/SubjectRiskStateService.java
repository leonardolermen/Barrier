package com.barrier.riskengine.riskstate.service;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.riskstate.domain.RiskLevelTransition;
import com.barrier.riskengine.riskstate.domain.SubjectRiskState;
import com.barrier.riskengine.riskstate.repository.interfaces.SubjectRiskStateRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mantém a projeção de risco corrente e informa quando o nível de risco de um cliente muda.
 *
 * <p><b>O upsert é monotônico no tempo da avaliação, não no da gravação.</b> Rescreening,
 * reavaliação por assurance e decisão manual concluem fora de ordem — uma avaliação iniciada antes
 * e concluída depois chegaria aqui por último e enterraria um estado mais novo. Ordenar por
 * {@code completedAt} da avaliação, e não pela ordem em que as transações commitam, é o que impede
 * a projeção de andar para trás.
 */
@Service
public class SubjectRiskStateService {

  private static final Logger log = LoggerFactory.getLogger(SubjectRiskStateService.class);

  private final SubjectRiskStateRepository repository;

  public SubjectRiskStateService(SubjectRiskStateRepository repository) {
    this.repository = repository;
  }

  /**
   * Grava o desfecho de uma avaliação concluída como risco corrente do cliente naquele tenant.
   *
   * <p>Chamado <b>dentro</b> da transação que conclui a avaliação: é projeção, não evento. Se a
   * avaliação commitou, o estado corrente commitou; fora da transação, uma falha entre as duas
   * gravações deixaria a projeção mentindo sem nada que a reconciliasse (ADR-0017 — a projeção não
   * tem dono de recovery próprio, ela segue a transação da avaliação).
   *
   * @return a transição de nível, quando houve uma; vazio na primeira avaliação do cliente, quando
   *     o nível não mudou, ou quando esta avaliação é mais antiga que o estado já gravado
   */
  @Transactional
  public Optional<RiskLevelTransition> record(Assessment assessment, int score, String engineVersion) {
    if (assessment.subjectId() == null || assessment.riskLevel() == null) {
      return Optional.empty();
    }
    UUID subjectId = UUID.fromString(assessment.subjectId());
    Instant evaluatedAt = determinedAt(assessment);

    Optional<SubjectRiskState> current = repository.find(subjectId, assessment.tenantId());
    if (current.isPresent() && !current.get().supersededBy(evaluatedAt)) {
      log.debug(
          "Avaliação {} concluída fora de ordem; risco corrente do subject preservado",
          assessment.id().asString());
      return Optional.empty();
    }

    repository.save(
        new SubjectRiskState(
            subjectId,
            assessment.tenantId(),
            assessment.riskLevel(),
            score,
            assessment.status(),
            assessment.id().value(),
            engineVersion,
            evaluatedAt,
            Instant.now()));

    return current
        .map(SubjectRiskState::level)
        .filter(previous -> previous != assessment.riskLevel())
        .map(previous -> new RiskLevelTransition(previous, assessment.riskLevel()));
  }

  /**
   * Atualiza a projeção depois de uma decisão humana (EDD), preservando score e versão do motor da
   * avaliação que já estava projetada.
   *
   * <p>Sem isto o corrente fica preso no que o motor decidiu <b>antes</b> do analista: um cliente
   * que o motor mandou para revisão e o analista reprovou apareceria na projeção com a decisão do
   * motor, e o {@code GET} responderia o contrário do que o parceiro decidiu. A decisão manual não
   * mexe no {@code riskLevel} — o nível de risco continua sendo o que o motor apurou —, então esta
   * atualização nunca produz transição de nível.
   */
  @Transactional
  public Optional<RiskLevelTransition> recordManualDecision(Assessment assessment) {
    if (assessment.subjectId() == null || assessment.riskLevel() == null) {
      return Optional.empty();
    }
    Optional<SubjectRiskState> current =
        repository.find(UUID.fromString(assessment.subjectId()), assessment.tenantId());
    return record(
        assessment,
        current.map(SubjectRiskState::score).orElse(0),
        current.map(SubjectRiskState::engineVersion).orElse(null));
  }

  /**
   * Quando este estado foi determinado. Decisão humana acontece <b>depois</b> da conclusão da
   * avaliação e é ela que vale: usar {@code completedAt} nos dois casos faria a decisão do analista
   * empatar com a do motor e ser descartada pela regra de ordenação.
   */
  private static Instant determinedAt(Assessment assessment) {
    if (assessment.reviewedAt() != null) {
      return assessment.reviewedAt();
    }
    return assessment.completedAt() == null ? Instant.now() : assessment.completedAt();
  }

  /** Risco corrente do cliente naquele tenant, se já houver avaliação concluída. */
  @Transactional(readOnly = true)
  public Optional<SubjectRiskState> find(UUID subjectId, String tenantId) {
    return repository.find(subjectId, tenantId);
  }
}
