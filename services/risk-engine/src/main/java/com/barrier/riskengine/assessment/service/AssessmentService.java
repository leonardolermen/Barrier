package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentId;
import com.barrier.riskengine.assessment.domain.AssessmentNotFoundException;
import com.barrier.riskengine.assessment.repository.AssessmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Casos de uso de avaliação: submeter e consultar. */
@Service
public class AssessmentService {

  private final AssessmentRepository repository;

  public AssessmentService(AssessmentRepository repository) {
    this.repository = repository;
  }

  /**
   * Cria uma avaliação em EM_ANALISE. O processamento (identity/screening/risk) ocorre de
   * forma assíncrona em {@link AssessmentProcessor}.
   */
  @Transactional
  public Assessment submit(SubmitAssessmentCommand command) {
    Assessment assessment =
        Assessment.submit(command.documentType(), command.document(), command.name());
    return repository.save(assessment);
  }

  @Transactional(readOnly = true)
  public Assessment get(AssessmentId id) {
    return repository.findById(id).orElseThrow(() -> new AssessmentNotFoundException(id));
  }
}
