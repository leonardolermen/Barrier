package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentId;
import com.barrier.riskengine.assessment.domain.AssessmentNotFoundException;
import com.barrier.riskengine.assessment.domain.Documents;
import com.barrier.riskengine.assessment.repository.AssessmentRepository;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Casos de uso de avaliação: submeter e consultar. */
@Service
public class AssessmentService {

  private final AssessmentRepository repository;
  private final SubjectService subjectService;

  public AssessmentService(AssessmentRepository repository, SubjectService subjectService) {
    this.repository = repository;
    this.subjectService = subjectService;
  }

  /**
   * Cria uma avaliação em EM_ANALISE. Antes, acha-ou-cria o subject (cliente final) por documento
   * e garante o vínculo do tenant com ele. O processamento ocorre de forma assíncrona em
   * {@link AssessmentProcessor}.
   */
  @Transactional
  public Assessment submit(SubmitAssessmentCommand command) {
    String digits = Documents.normalize(command.documentType(), command.document());
    Subject subject =
        subjectService.findOrCreate(command.documentType().name(), digits, command.name());
    subjectService.link(command.tenantId(), subject.id());

    Assessment assessment =
        Assessment.submit(
            command.tenantId(),
            subject.id().toString(),
            command.documentType(),
            command.document(),
            command.name());
    return repository.save(assessment);
  }

  /** Consulta escopada por tenant: uma avaliação de outro cliente responde como não encontrada. */
  @Transactional(readOnly = true)
  public Assessment get(AssessmentId id, String tenantId) {
    return repository
        .findById(id)
        .filter(a -> a.tenantId().equals(tenantId))
        .orElseThrow(() -> new AssessmentNotFoundException(id));
  }
}
