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
  private final AssessmentEventPublisher eventPublisher;

  public AssessmentService(
      AssessmentRepository repository,
      SubjectService subjectService,
      AssessmentEventPublisher eventPublisher) {
    this.repository = repository;
    this.subjectService = subjectService;
    this.eventPublisher = eventPublisher;
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
            command.name(),
            command.ip(),
            command.deviceId());
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

  /**
   * Decisão humana de uma avaliação em revisão (EDD), no escopo do tenant. Emite novamente o
   * evento de conclusão com o desfecho final (o webhook entrega ao cliente).
   */
  @Transactional
  public Assessment decide(
      AssessmentId id, String tenantId, boolean approve, String reviewedBy, String reason) {
    Assessment assessment = get(id, tenantId);
    assessment.decide(approve, reviewedBy, reason);
    Assessment saved = repository.save(assessment);
    eventPublisher.publishCompleted(saved);
    return saved;
  }
}
