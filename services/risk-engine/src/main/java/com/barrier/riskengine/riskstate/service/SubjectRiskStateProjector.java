package com.barrier.riskengine.riskstate.service;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.service.AssessmentCompletedListener;
import com.barrier.riskengine.riskstate.domain.RiskLevelTransition;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Projeta toda avaliação com desfecho no risco corrente do cliente.
 *
 * <p>É o lado que implementa {@link AssessmentCompletedListener} — o módulo {@code assessment}
 * declara o fato e não sabe quem reage a ele, que é o que mantém a dependência em uma direção só
 * (ver o Javadoc da interface).
 */
@Component
public class SubjectRiskStateProjector implements AssessmentCompletedListener {

  private final SubjectRiskStateService service;
  private final RiskLevelChangeEventPublisher eventPublisher;

  public SubjectRiskStateProjector(
      SubjectRiskStateService service, RiskLevelChangeEventPublisher eventPublisher) {
    this.service = service;
    this.eventPublisher = eventPublisher;
  }

  @Override
  public void onCompleted(Assessment assessment, Integer score, String engineVersion) {
    // Decisão humana não recalcula score e não mexe no nível de risco — o analista muda o desfecho.
    // Por isso ela nunca produz transição, e o `record` correspondente preserva o que o motor
    // apurou.
    Optional<RiskLevelTransition> transition =
        score == null
            ? service.recordManualDecision(assessment)
            : service.record(assessment, score, engineVersion);

    // Mesma transação da projeção: o evento só existe se o estado que ele anuncia existir.
    transition.ifPresent(t -> eventPublisher.publish(assessment, t, engineVersion));
  }
}
