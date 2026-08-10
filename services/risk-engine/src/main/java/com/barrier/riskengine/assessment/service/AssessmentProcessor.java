package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentId;
import com.barrier.riskengine.assessment.domain.AssessmentStatus;
import com.barrier.riskengine.assessment.repository.AssessmentRepository;
import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.identity.service.IdentityResult;
import com.barrier.riskengine.identity.service.IdentityService;
import com.barrier.riskengine.identity.service.VerifyIdentityCommand;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskDecision;
import com.barrier.riskengine.risk.rule.RiskContext;
import com.barrier.riskengine.risk.service.RiskScoringService;
import com.barrier.commons.name.NameNormalizer;
import com.barrier.commons.observability.Correlation;
import com.barrier.riskengine.screening.domain.ScreenedParty;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.screening.service.ScreeningCommand;
import com.barrier.riskengine.screening.service.ScreeningService;
import com.barrier.riskengine.subject.profile.domain.RegistrationCompleteness;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.domain.SubjectProfilePatch;
import com.barrier.riskengine.subject.profile.service.SubjectProfileService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processa avaliações pendentes: reúne os sinais (identidade + screening) e delega a decisão ao
 * motor de risco, que consolida tudo num score/nível com fatores explicáveis.
 *
 * <p>A recomendação do motor vira o status: APPROVE → APROVADO, REVIEW → EM_REVISAO, REJECT →
 * REPROVADO. Ao concluir, grava {@code barrier.assessment.completed} na outbox, na mesma
 * transação da mudança de estado.
 *
 * <p><b>Forma do processamento</b>, e por que ela é assim:
 *
 * <ol>
 *   <li><b>Reivindica</b> um lote em transação curta ({@code FOR UPDATE SKIP LOCKED}), para que
 *       réplicas concorrentes peguem conjuntos disjuntos;
 *   <li><b>processa cada avaliação isoladamente</b>, com as chamadas de bureau e screening
 *       <i>fora</i> de transação;
 *   <li><b>persiste o desfecho</b> de cada uma em sua própria transação, junto com o evento.
 * </ol>
 *
 * <p>Antes, o método inteiro era {@code @Transactional} sobre um lote de 50 com HTTP dentro: uma
 * conexão do pool ficava presa por minutos (um bureau lento esgotava o pool e derrubava a API), e
 * uma única exceção desfazia as 49 avaliações boas do lote — que voltavam à fila junto com a ruim,
 * para falhar de novo a cada 2 segundos, indefinidamente.
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
  private final AssessmentEventPublisher eventPublisher;
  private final AssessmentMetrics metrics;
  private final TransactionTemplate transactionTemplate;
  private final Duration lease;
  private final int maxAttempts;
  private final Duration baseBackoff;

  public AssessmentProcessor(
      AssessmentRepository repository,
      IdentityService identityService,
      ScreeningService screeningService,
      RiskScoringService riskScoringService,
      SubjectProfileService subjectProfileService,
      AssessmentEventPublisher eventPublisher,
      AssessmentMetrics metrics,
      TransactionTemplate transactionTemplate,
      @Value("${barrier.assessment.lease:PT5M}") Duration lease,
      @Value("${barrier.assessment.max-attempts:5}") int maxAttempts,
      @Value("${barrier.assessment.base-backoff:PT30S}") Duration baseBackoff) {
    this.repository = repository;
    this.identityService = identityService;
    this.screeningService = screeningService;
    this.riskScoringService = riskScoringService;
    this.subjectProfileService = subjectProfileService;
    this.eventPublisher = eventPublisher;
    this.metrics = metrics;
    this.transactionTemplate = transactionTemplate;
    this.lease = lease;
    this.maxAttempts = maxAttempts;
    this.baseBackoff = baseBackoff;
  }

  /** Executado periodicamente; também chamável diretamente (ex.: em testes). */
  @Scheduled(fixedDelayString = "${barrier.assessment.processor-delay-ms:2000}")
  public int process() {
    int processed = 0;
    for (AssessmentId id : repository.claimPending(BATCH, lease)) {
      if (processOne(id)) {
        processed++;
      }
    }
    return processed;
  }

  /**
   * Uma avaliação falha sozinha. O {@code catch} abrangente é intencional: qualquer exceção aqui
   * (provider quebrado, evidência que não cabe na coluna, config corrompida) tem de virar tentativa
   * contabilizada, nunca derrubar o lote nem repetir para sempre.
   */
  private boolean processOne(AssessmentId id) {
    MDC.put("assessmentId", id.asString());
    try {
      Assessment assessment = repository.findById(id).orElse(null);
      if (assessment == null || !assessment.isPending()) {
        return false; // concluída por outro caminho entre a reivindicação e agora
      }
      // Restaura a correlação da requisição original: sem isto, todo log da decisão nasce órfão —
      // ela roda noutra thread, minutos depois, onde o MDC do servlet já não existe.
      AtomicBoolean processed = new AtomicBoolean(false);
      Correlation.run(assessment.correlationId(), () -> processed.set(completeTracked(assessment)));
      return processed.get();
    } finally {
      MDC.remove("assessmentId");
    }
  }

  /** Mede e classifica o desfecho; as exceções continuam sendo tratadas como antes. */
  private boolean completeTracked(Assessment assessment) {
    AssessmentId id = assessment.id();
    try {
      metrics.timeProcessing(() -> complete(assessment));
      metrics.recordDecision(assessment);
      return true;
    } catch (OptimisticLockingFailureException e) {
      // Perdemos a corrida: outra réplica concluiu esta avaliação enquanto consultávamos os
      // provedores. Não é falha desta avaliação — é o controle de concorrência funcionando. A
      // transação (incluindo o evento na outbox) já foi desfeita; contabilizar tentativa aqui
      // marcaria como problemática uma avaliação que foi decidida corretamente.
      log.info("Avaliação {} já foi concluída por outro processo; descartando esta execução", id.asString());
      return false;
    } catch (RuntimeException e) {
      recordFailure(id, e);
      metrics.recordFailure();
      return false;
    }
  }

  private void complete(Assessment assessment) {
    // Fora de transação: são chamadas de rede, e prender conexão de banco durante elas foi o que
    // esgotava o pool quando um bureau ficava lento.
    IdentityResult identity =
        identityService.verify(
            new VerifyIdentityCommand(
                assessment.id().asString(),
                assessment.documentType().name(),
                assessment.documentDigits(),
                assessment.name()));

    UUID subjectId = UUID.fromString(assessment.subjectId());
    // O cadastro é do par subject × tenant: o enriquecimento pelo bureau alimenta o dossiê deste
    // tenant, e o gate de completude avalia o que este tenant declarou — não o que outro declarou.
    if (identity.company() != null) {
      persistCompanyProfile(subjectId, assessment.tenantId(), identity.company());
    }
    // O cadastro é lido ANTES do screening: é dele que sai o representante legal a consultar.
    SubjectProfile profile = subjectProfileService.find(subjectId, assessment.tenantId());

    ScreeningResult screening =
        screeningService.screen(
            new ScreeningCommand(
                assessment.id().asString(),
                assessment.documentType().name(),
                assessment.documentDigits(),
                assessment.name(),
                relatedParties(identity.company(), profile)));

    RiskDecision decision =
        riskScoringService.score(
            new RiskContext(
                assessment.id().asString(),
                assessment.tenantId(),
                identity.check(),
                screening,
                identity.company(),
                profile));

    AssessmentStatus finalStatus = toStatus(decision.recommendation());
    List<String> factors = new ArrayList<>(decision.explanations());
    RegistrationCompleteness completeness =
        RegistrationCompleteness.evaluate(assessment.documentType().name(), profile);
    if (!completeness.complete() && finalStatus == AssessmentStatus.APROVADO) {
      finalStatus = AssessmentStatus.EM_REVISAO;
      factors.add("Cadastro incompleto: " + String.join(", ", completeness.missingFields()));
    }

    assessment.complete(decision.level(), finalStatus, decision.summary(), factors);

    // Estado e evento na mesma transação (transactional outbox) — e só esta parte é transacional.
    transactionTemplate.executeWithoutResult(
        status -> {
          repository.save(assessment);
          eventPublisher.publishCompleted(assessment);
        });

    log.info(
        "Avaliação {} concluída: {} (score {}, motor {})",
        assessment.id().asString(),
        assessment.status(),
        decision.totalScore(),
        decision.engineVersion());
  }

  /** Contabiliza a tentativa em transação própria: a falha precisa sobreviver ao rollback. */
  private void recordFailure(AssessmentId id, RuntimeException error) {
    try {
      transactionTemplate.executeWithoutResult(
          status ->
              repository
                  .findById(id)
                  .filter(Assessment::isPending)
                  .ifPresent(
                      assessment -> {
                        assessment.recordFailure(
                            error.toString(), maxAttempts, nextAttempt(assessment.attempts()));
                        repository.save(assessment);
                        if (assessment.status() == AssessmentStatus.FALHA_PROCESSAMENTO) {
                          log.error(
                              "Avaliação {} esgotou as {} tentativas e foi marcada como falha",
                              id.asString(),
                              maxAttempts,
                              error);
                        } else {
                          log.warn(
                              "Avaliação {} falhou (tentativa {}/{}); nova tentativa em {}",
                              id.asString(),
                              assessment.attempts(),
                              maxAttempts,
                              assessment.nextAttemptAt(),
                              error);
                        }
                      }));
    } catch (RuntimeException e) {
      // Não relança: se nem a gravação da falha funciona, o banco está indisponível e insistir
      // aqui só derrubaria o lote inteiro. A lease expira e a avaliação volta a ser reivindicável.
      log.error("Não foi possível registrar a falha da avaliação {}", id.asString(), e);
    }
  }

  private Instant nextAttempt(int attempts) {
    long factor = 1L << Math.min(attempts, 6); // backoff exponencial, teto no 64x
    return Instant.now().plus(baseBackoff.multipliedBy(factor));
  }

  /**
   * Partes relacionadas a consultar nas listas, além do titular: sócios do QSA e representante
   * legal declarado.
   *
   * <p>Deduplica por nome normalizado — o representante legal costuma ser também sócio, e sem isso
   * o mesmo apontamento apareceria duas vezes na trilha, sugerindo dois problemas onde há um.
   */
  private static List<ScreenedParty> relatedParties(CompanyProfile company, SubjectProfile profile) {
    Map<String, ScreenedParty> byName = new LinkedHashMap<>();
    if (company != null) {
      company.partners().stream()
          .filter(partner -> partner.name() != null && !partner.name().isBlank())
          .forEach(partner -> byName.putIfAbsent(key(partner.name()), ScreenedParty.socio(partner.name())));
    }
    if (profile != null
        && profile.legalRepresentativeName() != null
        && !profile.legalRepresentativeName().isBlank()) {
      byName.putIfAbsent(
          key(profile.legalRepresentativeName()),
          ScreenedParty.representanteLegal(
              profile.legalRepresentativeName(), profile.legalRepresentativeDocument()));
    }
    return List.copyOf(byName.values());
  }

  private static String key(String name) {
    return NameNormalizer.normalize(name);
  }

  /**
   * Persiste os dados objetivos de PJ vindos do bureau (fundação, CNAE, QSA) no cadastro do
   * subject — antes esses dados eram descartados depois de alimentar as risk rules.
   */
  private void persistCompanyProfile(UUID subjectId, String tenantId, CompanyProfile company) {
    List<SubjectProfile.Partner> partners =
        company.partners().stream()
            .map(
                p ->
                    new SubjectProfile.Partner(
                        p.name(), p.legalEntity(), p.foreign(), p.qualification()))
            .toList();
    subjectProfileService.upsert(
        subjectId,
        tenantId,
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
