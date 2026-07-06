package com.barrier.riskengine.assessment.service;

import com.barrier.commons.outbox.OutboxRecorder;
import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentStatus;
import com.barrier.riskengine.assessment.domain.RiskLevel;
import com.barrier.riskengine.assessment.repository.AssessmentRepository;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.service.IdentityService;
import com.barrier.riskengine.identity.service.VerifyIdentityCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Processa avaliações pendentes.
 *
 * <p>Fase 2: executa a verificação de identidade. Identidade reprovada (NOT_FOUND/MISMATCH)
 * → REPROVADO; verificada ou bureau indisponível → APROVADO (screening e risco entram nas
 * fases seguintes). Ao concluir, grava {@code barrier.assessment.completed} na outbox, na
 * mesma transação da mudança de estado.
 */
@Component
public class AssessmentProcessor {

  private static final Logger log = LoggerFactory.getLogger(AssessmentProcessor.class);
  private static final String EVENT_TYPE = "barrier.assessment.completed";
  private static final int EVENT_VERSION = 1;
  private static final int BATCH = 50;

  private final AssessmentRepository repository;
  private final IdentityService identityService;
  private final OutboxRecorder outbox;
  private final ObjectMapper objectMapper;

  public AssessmentProcessor(
      AssessmentRepository repository,
      IdentityService identityService,
      OutboxRecorder outbox,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.identityService = identityService;
    this.outbox = outbox;
    this.objectMapper = objectMapper;
  }

  /** Executado periodicamente; também chamável diretamente (ex.: em testes). */
  @Scheduled(fixedDelayString = "${barrier.assessment.processor-delay-ms:2000}")
  @Transactional
  public int process() {
    int processed = 0;
    for (Assessment assessment : repository.findPending(BATCH)) {
      complete(assessment);
      processed++;
    }
    return processed;
  }

  private void complete(Assessment assessment) {
    IdentityCheck identity =
        identityService.verify(
            new VerifyIdentityCommand(
                assessment.id().asString(),
                assessment.documentType().name(),
                assessment.documentDigits(),
                assessment.name()));

    if (identity.isRejected()) {
      assessment.complete(
          RiskLevel.ALTO,
          AssessmentStatus.REPROVADO,
          "Identidade não confirmada (" + identity.status() + ")");
    } else {
      assessment.complete(
          RiskLevel.BAIXO,
          AssessmentStatus.APROVADO,
          "Identidade " + identity.status().name().toLowerCase());
    }

    repository.save(assessment);
    outbox.record(
        assessment.id().asString(),
        EVENT_TYPE,
        EVENT_VERSION,
        serialize(AssessmentCompletedPayload.from(assessment)));
    log.info(
        "Avaliação {} concluída: {} (identidade {})",
        assessment.id().asString(),
        assessment.status(),
        identity.status());
  }

  private String serialize(AssessmentCompletedPayload payload) {
    return objectMapper.writeValueAsString(payload);
  }
}
