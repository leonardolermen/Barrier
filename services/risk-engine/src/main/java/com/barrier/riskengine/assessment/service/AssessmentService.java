package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentId;
import com.barrier.riskengine.assessment.domain.exceptions.AssessmentNotFoundException;
import com.barrier.riskengine.assessment.domain.documents.Documents;
import com.barrier.riskengine.assessment.domain.exceptions.IdempotencyConflictException;
import com.barrier.riskengine.assessment.domain.IdempotencyReservation;
import com.barrier.riskengine.assessment.repository.interfaces.AssessmentRepository;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Casos de uso de avaliação: submeter e consultar. */
@Service
public class AssessmentService {

  private static final Logger log = LoggerFactory.getLogger(AssessmentService.class);

  private final AssessmentRepository repository;
  private final SubjectService subjectService;
  private final AssessmentEventPublisher eventPublisher;
  private final IdempotencyService idempotency;

  public AssessmentService(
      AssessmentRepository repository,
      SubjectService subjectService,
      AssessmentEventPublisher eventPublisher,
      IdempotencyService idempotency) {
    this.repository = repository;
    this.subjectService = subjectService;
    this.eventPublisher = eventPublisher;
    this.idempotency = idempotency;
  }

  /**
   * Cria uma avaliação em EM_ANALISE. Antes, acha-ou-cria o subject (cliente final) por documento
   * e garante o vínculo do tenant com ele. O processamento ocorre de forma assíncrona em
   * {@link AssessmentProcessor}.
   *
   * <p>Com {@code Idempotency-Key}, a mesma requisição reenviada dentro da janela devolve a
   * avaliação original em vez de criar outra. Isso não é só economia de chamada de bureau: duas
   * avaliações do mesmo cliente feitas em momentos diferentes podem decidir diferente, e aí o
   * retry vira um oráculo — basta tentar até o bureau falhar ou a lista estar desatualizada. A
   * chave é escopada por tenant; a de um cliente nunca colide com a de outro.
   *
   * <p>O documento é normalizado (e portanto validado) antes da reserva: requisição inválida
   * responde 400 sem queimar a chave.
   */
  @Transactional
  public SubmissionResult submit(SubmitAssessmentCommand command) {
    String digits = Documents.normalize(command.documentType(), command.document());
    if (!command.hasIdempotencyKey()) {
      return new SubmissionResult(create(command, digits), false);
    }

    String key = command.idempotencyKey();
    String fingerprint = command.fingerprint(digits);
    IdempotencyReservation reservation = idempotency.reserve(command.tenantId(), key, fingerprint);

    if (!reservation.fresh()) {
      if (!reservation.requestHash().equals(fingerprint)) {
        throw IdempotencyConflictException.differentRequest(key);
      }
      if (reservation.inProgress()) {
        throw IdempotencyConflictException.inProgress(key);
      }
      Optional<Assessment> original = repository.findById(reservation.assessmentId());
      if (original.isPresent()) {
        return new SubmissionResult(original.get(), true);
      }
      // Chave apontando para avaliação que não existe: a submissão original não chegou a commitar
      // (falha no commit, depois do bind). A reserva não tem o que repetir, então é descartada e
      // esta requisição segue como submissão nova — melhor que devolver 404 para um POST.
      log.warn("Reserva de idempotência órfã descartada (tenant={}, chave={})", command.tenantId(), key);
      idempotency.release(command.tenantId(), key);
      idempotency.reserve(command.tenantId(), key, fingerprint);
    }

    try {
      Assessment created = create(command, digits);
      idempotency.bind(command.tenantId(), key, created.id());
      return new SubmissionResult(created, false);
    } catch (RuntimeException e) {
      // A liberação roda em transação própria: sem ela a chave ficaria travada até o fim da janela
      // por causa de uma submissão que não criou nada, e o retry legítimo do cliente receberia 409.
      idempotency.release(command.tenantId(), key);
      throw e;
    }
  }

  private Assessment create(SubmitAssessmentCommand command, String digits) {
    Subject subject =
        subjectService.findOrCreate(command.documentType().name(), digits, command.name());
    subjectService.link(command.tenantId(), subject.id());

    Assessment assessment =
        switch (command.origin()) {
          case ONBOARDING ->
              Assessment.submit(
                  command.tenantId(),
                  subject.id().toString(),
                  command.documentType(),
                  command.document(),
                  command.name());
          case RESCREENING ->
              Assessment.rescreen(
                  command.tenantId(),
                  subject.id().toString(),
                  command.documentType(),
                  command.document(),
                  command.name(),
                  command.originDetail());
          case ASSURANCE ->
              Assessment.assurance(
                  command.tenantId(),
                  subject.id().toString(),
                  command.documentType(),
                  command.document(),
                  command.name(),
                  command.originDetail());
        };
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
      AssessmentId id,
      String tenantId,
      boolean approve,
      String reviewedBy,
      String reviewedByKey,
      String reason) {
    Assessment assessment = get(id, tenantId);
    assessment.decide(approve, reviewedBy, reviewedByKey, reason);
    Assessment saved = repository.save(assessment);
    eventPublisher.publishCompleted(saved);
    return saved;
  }
}
