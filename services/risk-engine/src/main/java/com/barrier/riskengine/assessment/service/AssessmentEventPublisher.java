package com.barrier.riskengine.assessment.service;

import com.barrier.commons.outbox.OutboxRecorder;
import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.identity.service.IdentityProvenanceService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publica {@code barrier.assessment.completed} na outbox. Usado tanto pela conclusão automática
 * (processor) quanto pela decisão manual — em ambos os casos o evento reflete o estado atual da
 * avaliação, e o webhook entrega o desfecho ao cliente.
 */
@Component
public class AssessmentEventPublisher {

  static final String EVENT_TYPE = "barrier.assessment.completed";
  static final int EVENT_VERSION = 1;

  private final OutboxRecorder outbox;
  private final ObjectMapper objectMapper;
  private final IdentityProvenanceService identityProvenance;

  public AssessmentEventPublisher(
      OutboxRecorder outbox, ObjectMapper objectMapper, IdentityProvenanceService identityProvenance) {
    this.outbox = outbox;
    this.objectMapper = objectMapper;
    this.identityProvenance = identityProvenance;
  }

  public void publishCompleted(Assessment assessment) {
    // A correlação vem do agregado, não do MDC: a conclusão automática roda num @Scheduled, onde o
    // contexto da requisição original não existe mais.
    var provenance = identityProvenance.forAssessment(assessment.id().asString()).orElse(null);
    outbox.record(
        assessment.id().asString(),
        EVENT_TYPE,
        EVENT_VERSION,
        objectMapper.writeValueAsString(AssessmentCompletedPayload.from(assessment, provenance)),
        assessment.correlationId());
  }
}
