package com.barrier.riskengine.assessment.domain.exceptions;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentId;

/** Lançada quando uma avaliação não é encontrada pelo id. */
public class AssessmentNotFoundException extends RuntimeException {

  public AssessmentNotFoundException(AssessmentId id) {
    super("Avaliação não encontrada: " + id.asString());
  }
}
