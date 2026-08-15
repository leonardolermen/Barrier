package com.barrier.riskengine.riskstate.service;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.service.AssessmentCompletedListener;
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

  public SubjectRiskStateProjector(SubjectRiskStateService service) {
    this.service = service;
  }

  @Override
  public void onCompleted(Assessment assessment, Integer score, String engineVersion) {
    if (score == null) {
      // Decisão humana: o analista muda o desfecho, não o score. Preserva o que o motor apurou.
      service.recordManualDecision(assessment);
      return;
    }
    service.record(assessment, score, engineVersion);
  }
}
