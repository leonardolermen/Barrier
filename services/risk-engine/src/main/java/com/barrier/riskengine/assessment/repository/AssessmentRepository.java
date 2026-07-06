package com.barrier.riskengine.assessment.repository;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentId;
import java.util.List;
import java.util.Optional;

/**
 * Repositório de domínio de avaliações. O {@code service} depende desta interface, não da
 * implementação JPA.
 */
public interface AssessmentRepository {

  Assessment save(Assessment assessment);

  Optional<Assessment> findById(AssessmentId id);

  /** Avaliações pendentes (EM_ANALISE), mais antigas primeiro. */
  List<Assessment> findPending(int limit);
}
