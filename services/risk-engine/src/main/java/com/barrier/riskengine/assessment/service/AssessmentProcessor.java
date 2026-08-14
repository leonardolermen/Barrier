package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentId;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import com.barrier.riskengine.assessment.repository.interfaces.AssessmentRepository;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.service.AssuranceService;
import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.identity.domain.PersonProfile;
import com.barrier.riskengine.identity.service.IdentityResult;
import com.barrier.riskengine.identity.service.IdentityService;
import com.barrier.riskengine.identity.service.VerifyIdentityCommand;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskDecision;
import com.barrier.riskengine.risk.rule.context.AssuranceSummary;
import com.barrier.riskengine.risk.rule.context.RiskContext;
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
import com.barrier.riskengine.subject.profile.domain.VerifiableField;
import com.barrier.riskengine.subject.profile.service.FieldVerificationService;
import com.barrier.riskengine.subject.profile.service.RegistryValidationService;
import com.barrier.riskengine.subject.profile.service.SubjectProfileService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private final FieldVerificationService fieldVerificationService;
  private final RegistryValidationService registryValidationService;
  private final AssuranceService assuranceService;
  private final AssessmentEventPublisher eventPublisher;
  private final AssessmentMetrics metrics;
  private final TransactionTemplate transactionTemplate;
  private final Duration lease;
  private final int maxAttempts;
  private final Duration baseBackoff;
  private final boolean requireVerification;

  public AssessmentProcessor(
      AssessmentRepository repository,
      IdentityService identityService,
      ScreeningService screeningService,
      RiskScoringService riskScoringService,
      SubjectProfileService subjectProfileService,
      FieldVerificationService fieldVerificationService,
      RegistryValidationService registryValidationService,
      AssuranceService assuranceService,
      AssessmentEventPublisher eventPublisher,
      AssessmentMetrics metrics,
      TransactionTemplate transactionTemplate,
      @Value("${barrier.assessment.lease:PT5M}") Duration lease,
      @Value("${barrier.assessment.max-attempts:5}") int maxAttempts,
      @Value("${barrier.assessment.base-backoff:PT30S}") Duration baseBackoff,
      @Value("${barrier.verification.required:true}") boolean requireVerification) {
    this.repository = repository;
    this.identityService = identityService;
    this.screeningService = screeningService;
    this.riskScoringService = riskScoringService;
    this.subjectProfileService = subjectProfileService;
    this.fieldVerificationService = fieldVerificationService;
    this.registryValidationService = registryValidationService;
    this.assuranceService = assuranceService;
    this.eventPublisher = eventPublisher;
    this.metrics = metrics;
    this.transactionTemplate = transactionTemplate;
    this.lease = lease;
    this.maxAttempts = maxAttempts;
    this.baseBackoff = baseBackoff;
    this.requireVerification = requireVerification;
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
                assessment.tenantId(),
                assessment.documentType().name(),
                assessment.documentDigits(),
                assessment.name()));

    UUID subjectId = UUID.fromString(assessment.subjectId());
    // O cadastro é do par subject × tenant: o enriquecimento pelo bureau alimenta o dossiê deste
    // tenant, e o gate de completude avalia o que este tenant declarou — não o que outro declarou.
    if (identity.company() != null) {
      persistCompanyProfile(subjectId, assessment.tenantId(), identity.company());
    }
    // Simétrico do de PJ. Sem isto, os dados objetivos de pessoa física que o bureau já devolveu
    // eram descartados, e TODA avaliação de PF era rebaixada para revisão por cadastro incompleto —
    // enchendo a fila de EDD com casos que não pedem julgamento humano nenhum.
    if (identity.person() != null) {
      persistPersonProfile(subjectId, assessment.tenantId(), identity.person());
    }
    // O cadastro é lido ANTES do screening: é dele que sai o representante legal a consultar.
    SubjectProfile profile = subjectProfileService.find(subjectId, assessment.tenantId());

    // Nascimento declarado × nascimento do bureau. Concordância vira verificação; divergência não
    // vira exceção nem reprovação — cai no gate de completude como campo não conferido, e o
    // desfecho é pedir documento, não recusar.
    if (identity.person() != null) {
      fieldVerificationService.recordBirthDateFromBureau(
          subjectId,
          assessment.tenantId(),
          profile.birthDate(),
          identity.person().birthDate(),
          identity.check() == null ? null : "identity-check:" + assessment.id().asString());
    }

    ScreeningResult screening =
        screeningService.screen(
            new ScreeningCommand(
                assessment.id().asString(),
                assessment.documentType().name(),
                assessment.documentDigits(),
                assessment.name(),
                relatedParties(identity.company(), profile)));

    // Três chamadas, não seis: documento e biometria vêm de latest() (a última verificação
    // decide), e o total de tentativas de biometria vem de uma contagem à parte — é o sinal de
    // quem testa artefato até vencer o detector, e a última tentativa sozinha apaga esse rastro.
    AssuranceSummary assurance =
        new AssuranceSummary(
            assuranceService
                .latest(subjectId, assessment.tenantId(), AssuranceKind.DOCUMENT)
                .orElse(null),
            assuranceService
                .latest(subjectId, assessment.tenantId(), AssuranceKind.BIOMETRIC)
                .orElse(null),
            assuranceService.attempts(subjectId, assessment.tenantId(), AssuranceKind.BIOMETRIC));

    RiskDecision decision =
        riskScoringService.score(
            new RiskContext(
                assessment.id().asString(),
                assessment.tenantId(),
                identity.check(),
                screening,
                identity.company(),
                profile,
                assurance));

    AssessmentStatus finalStatus = toStatus(decision.recommendation());
    List<String> factors = new ArrayList<>(decision.explanations());
    // Com verificação exigida, cadastro preenchido e não conferido deixa de liberar aprovação
    // automática. É mudança de contrato para quem já integra: toda PF passa a cair em
    // SOLICITAR_DOCUMENTO até o parceiro rodar o OTP. A chave existe para a migração ser
    // escalonada, e nasce LIGADA — desligada por padrão, seria o controle que não controla.
    RegistrationCompleteness completeness =
        requireVerification
            ? RegistrationCompleteness.evaluate(
                assessment.documentType().name(),
                profile,
                fieldVerificationService.verifiedFields(subjectId, assessment.tenantId(), profile))
            : RegistrationCompleteness.evaluate(assessment.documentType().name(), profile);
    // Validação cadastral (Datavalid/Serpro) só entra aqui: é o único ponto em que o gate está
    // prestes a rebaixar a avaliação por causa exata que ela sabe fechar (nascimento declarado e
    // não conferido). Chamar em qualquer outro caso queimaria consulta paga sem poder mudar o
    // desfecho (ADR-0015) — cadastro já verificado por OTP/bureau, ou faltando campo que o
    // Datavalid não cobre (ocupação, por exemplo), não passa por aqui.
    if (requireVerification
        && finalStatus == AssessmentStatus.APROVADO
        && !completeness.complete()
        && completeness.missingFields().contains(RegistrationCompleteness.BIRTH_DATE_NOT_VERIFIED)) {
      Set<VerifiableField> updatedVerifiedFields =
          registryValidationService.verifyIfWorthwhile(
              subjectId,
              assessment.tenantId(),
              assessment.documentType().name(),
              assessment.documentDigits(),
              assessment.name(),
              profile,
              fieldVerificationService.verifiedFields(subjectId, assessment.tenantId(), profile),
              assessment.id().asString());
      // Reavaliar a completude é o que evita rebaixar uma avaliação que acabou de ser conferida
      // — sem isso, a consulta paga teria sido feita e desperdiçada mesmo assim.
      completeness =
          RegistrationCompleteness.evaluate(
              assessment.documentType().name(), profile, updatedVerifiedFields);
    }
    if (!completeness.complete() && finalStatus == AssessmentStatus.APROVADO) {
      // Fila própria, não EDD: falta de campo cadastral não pede analista, pede o campo. Enquanto
      // isso virava EM_REVISAO, o que o time de operações mais via era justamente o que menos
      // precisava dele. Também não vira reprovação — ver AssessmentStatus.SOLICITAR_DOCUMENTO.
      finalStatus = AssessmentStatus.SOLICITAR_DOCUMENTO;
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

  /**
   * Persiste os dados objetivos de PF vindos do bureau (nascimento, nacionalidade, endereço).
   *
   * <p>É um {@code patch}: campo que o bureau não trouxe preserva o que o parceiro já declarou, e
   * nunca sobrescreve com nulo. Ocupação não entra porque bureau não a fornece — segue sendo
   * declaração do cliente, e é correto que o gate de completude continue cobrando-a.
   */
  private void persistPersonProfile(UUID subjectId, String tenantId, PersonProfile person) {
    PersonProfile.Address from = person.address();
    SubjectProfile.Address address =
        from == null
            ? null
            : new SubjectProfile.Address(
                from.street(),
                from.number(),
                from.complement(),
                from.district(),
                from.city(),
                from.state(),
                from.zipCode());
    subjectProfileService.upsert(
        subjectId,
        tenantId,
        new SubjectProfilePatch(
            person.birthDate(),
            null,
            person.nationality(),
            null,
            null,
            address,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));
  }

  private static AssessmentStatus toStatus(RiskRecommendation recommendation) {
    return switch (recommendation) {
      case APPROVE -> AssessmentStatus.APROVADO;
      case REVIEW -> AssessmentStatus.EM_REVISAO;
      case REJECT -> AssessmentStatus.REPROVADO;
    };
  }
}
