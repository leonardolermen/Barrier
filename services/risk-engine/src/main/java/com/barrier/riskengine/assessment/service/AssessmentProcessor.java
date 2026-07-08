package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentStatus;
import com.barrier.riskengine.assessment.repository.AssessmentRepository;
import com.barrier.riskengine.identity.service.IdentityResult;
import com.barrier.riskengine.identity.service.IdentityService;
import com.barrier.riskengine.identity.service.VerifyIdentityCommand;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskDecision;
import com.barrier.riskengine.risk.rule.RiskContext;
import com.barrier.riskengine.risk.service.RiskScoringService;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.screening.service.ScreeningCommand;
import com.barrier.riskengine.screening.service.ScreeningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processa avaliações pendentes: reúne os sinais (identidade + screening) e delega a decisão
 * ao motor de risco, que consolida tudo num score/nível com fatores explicáveis.
 *
 * <p>A recomendação do motor vira o status: APPROVE → APROVADO, REVIEW → EM_REVISAO,
 * REJECT → REPROVADO. Ao concluir, grava {@code barrier.assessment.completed} na outbox, na
 * mesma transação da mudança de estado.
 */
@Component
public class AssessmentProcessor {

  private static final Logger log = LoggerFactory.getLogger(AssessmentProcessor.class);
  private static final int BATCH = 50;

  private final AssessmentRepository repository;
  private final IdentityService identityService;
  private final ScreeningService screeningService;
  private final RiskScoringService riskScoringService;
  private final AssessmentEventPublisher eventPublisher;

  public AssessmentProcessor(
      AssessmentRepository repository,
      IdentityService identityService,
      ScreeningService screeningService,
      RiskScoringService riskScoringService,
      AssessmentEventPublisher eventPublisher) {
    this.repository = repository;
    this.identityService = identityService;
    this.screeningService = screeningService;
    this.riskScoringService = riskScoringService;
    this.eventPublisher = eventPublisher;
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
    IdentityResult identity =
        identityService.verify(
            new VerifyIdentityCommand(
                assessment.id().asString(),
                assessment.documentType().name(),
                assessment.documentDigits(),
                assessment.name()));

    ScreeningResult screening =
        screeningService.screen(
            new ScreeningCommand(
                assessment.id().asString(),
                assessment.documentType().name(),
                assessment.documentDigits(),
                assessment.name()));

    RiskDecision decision =
        riskScoringService.score(
            new RiskContext(
                assessment.id().asString(), identity.check(), screening, identity.company()));

    assessment.complete(
        decision.level(),
        toStatus(decision.recommendation()),
        decision.summary(),
        decision.explanations());

    repository.save(assessment);
    eventPublisher.publishCompleted(assessment);
    log.info(
        "Avaliação {} concluída: {} (score {}, motor {})",
        assessment.id().asString(),
        assessment.status(),
        decision.totalScore(),
        decision.engineVersion());
  }

  private static AssessmentStatus toStatus(RiskRecommendation recommendation) {
    return switch (recommendation) {
      case APPROVE -> AssessmentStatus.APROVADO;
      case REVIEW -> AssessmentStatus.EM_REVISAO;
      case REJECT -> AssessmentStatus.REPROVADO;
    };
  }
}
