package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentStatus;
import com.barrier.riskengine.assessment.repository.AssessmentRepository;
import com.barrier.riskengine.device.service.DeviceSeenService;
import com.barrier.riskengine.identity.domain.CompanyProfile;
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
import com.barrier.riskengine.subject.profile.domain.RegistrationCompleteness;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.domain.SubjectProfilePatch;
import com.barrier.riskengine.subject.profile.service.SubjectProfileService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
  private final SubjectProfileService subjectProfileService;
  private final DeviceSeenService deviceSeenService;
  private final AssessmentEventPublisher eventPublisher;

  public AssessmentProcessor(
      AssessmentRepository repository,
      IdentityService identityService,
      ScreeningService screeningService,
      RiskScoringService riskScoringService,
      SubjectProfileService subjectProfileService,
      DeviceSeenService deviceSeenService,
      AssessmentEventPublisher eventPublisher) {
    this.repository = repository;
    this.identityService = identityService;
    this.screeningService = screeningService;
    this.riskScoringService = riskScoringService;
    this.subjectProfileService = subjectProfileService;
    this.deviceSeenService = deviceSeenService;
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

    UUID subjectId = UUID.fromString(assessment.subjectId());
    if (identity.company() != null) {
      persistCompanyProfile(subjectId, identity.company());
    }
    SubjectProfile profile = subjectProfileService.find(subjectId);
    long deviceReuseCount =
        assessment.deviceId() == null
            ? 0
            : deviceSeenService.recordAndCountDistinctSubjects(
                assessment.tenantId(), assessment.deviceId(), subjectId);

    RiskDecision decision =
        riskScoringService.score(
            new RiskContext(
                assessment.id().asString(),
                assessment.tenantId(),
                identity.check(),
                screening,
                identity.company(),
                profile,
                assessment.ip(),
                deviceReuseCount));

    AssessmentStatus finalStatus = toStatus(decision.recommendation());
    List<String> factors = new ArrayList<>(decision.explanations());
    RegistrationCompleteness completeness =
        RegistrationCompleteness.evaluate(assessment.documentType().name(), profile);
    if (!completeness.complete() && finalStatus == AssessmentStatus.APROVADO) {
      finalStatus = AssessmentStatus.EM_REVISAO;
      factors.add("Cadastro incompleto: " + String.join(", ", completeness.missingFields()));
    }

    assessment.complete(decision.level(), finalStatus, decision.summary(), factors);

    repository.save(assessment);
    eventPublisher.publishCompleted(assessment);
    log.info(
        "Avaliação {} concluída: {} (score {}, motor {})",
        assessment.id().asString(),
        assessment.status(),
        decision.totalScore(),
        decision.engineVersion());
  }

  /**
   * Persiste os dados objetivos de PJ vindos do bureau (fundação, CNAE, QSA) no cadastro do
   * subject — antes esses dados eram descartados depois de alimentar as risk rules.
   */
  private void persistCompanyProfile(UUID subjectId, CompanyProfile company) {
    List<SubjectProfile.Partner> partners =
        company.partners().stream()
            .map(
                p ->
                    new SubjectProfile.Partner(
                        p.name(), p.legalEntity(), p.foreign(), p.qualification()))
            .toList();
    subjectProfileService.upsert(
        subjectId,
        new SubjectProfilePatch(
            null,
            company.openingDate(),
            null,
            null,
            null,
            null,
            null,
            null,
            company.cnaeCode(),
            company.cnaeDescription(),
            null,
            null,
            null,
            partners));
  }

  private static AssessmentStatus toStatus(RiskRecommendation recommendation) {
    return switch (recommendation) {
      case APPROVE -> AssessmentStatus.APROVADO;
      case REVIEW -> AssessmentStatus.EM_REVISAO;
      case REJECT -> AssessmentStatus.REPROVADO;
    };
  }
}
