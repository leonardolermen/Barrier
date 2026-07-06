package com.barrier.riskengine.assessment.domain;

/** Lançada quando uma avaliação não é encontrada pelo id. */
public class AssessmentNotFoundException extends RuntimeException {

  public AssessmentNotFoundException(AssessmentId id) {
    super("Avaliação não encontrada: " + id.asString());
  }
}
