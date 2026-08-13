package com.barrier.riskengine.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.repository.interfaces.AssessmentRepository;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.assurance.service.AssuranceService;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.identity.service.IdentityResult;
import com.barrier.riskengine.identity.service.IdentityService;
import com.barrier.riskengine.identity.service.VerifyIdentityCommand;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskDecision;
import com.barrier.riskengine.risk.registry.service.RiskRuleRegistryService;
import com.barrier.riskengine.risk.repository.interfaces.RiskScoreRepository;
import com.barrier.riskengine.risk.rule.ConsistencyRiskRule;
import com.barrier.riskengine.risk.rule.CorporateStructureRiskRule;
import com.barrier.riskengine.risk.rule.DebarmentRiskRule;
import com.barrier.riskengine.risk.rule.IdentityAssuranceRiskRule;
import com.barrier.riskengine.risk.rule.IdentityRiskRule;
import com.barrier.riskengine.risk.rule.NegativeMediaRiskRule;
import com.barrier.riskengine.risk.rule.NewCompanyRiskRule;
import com.barrier.riskengine.risk.rule.PepRiskRule;
import com.barrier.riskengine.risk.rule.SanctionRiskRule;
import com.barrier.riskengine.risk.rule.ScreeningCoverageRiskRule;
import com.barrier.riskengine.risk.rule.SensitiveCnaeRiskRule;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import com.barrier.riskengine.risk.service.RiskScoringService;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.watchlist.WatchlistImportStatus;
import com.barrier.riskengine.tenant.config.service.TenantRiskConfigService;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.screening.service.ScreeningCommand;
import com.barrier.riskengine.screening.service.ScreeningService;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.service.SubjectProfileService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class AssessmentProcessorTest {

  @Mock AssessmentRepository repository;
  @Mock IdentityService identityService;
  @Mock ScreeningService screeningService;
  @Mock RiskScoringService riskScoringService;
  @Mock SubjectProfileService subjectProfileService;
  @Mock com.barrier.riskengine.subject.profile.service.FieldVerificationService fieldVerificationService;
  @Mock AssuranceService assuranceService;
  @Mock AssessmentEventPublisher eventPublisher;

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private AssessmentProcessor newProcessor() {
    return new AssessmentProcessor(
        repository,
        identityService,
        screeningService,
        riskScoringService,
        subjectProfileService,
        fieldVerificationService,
        assuranceService,
        eventPublisher,
        new AssessmentMetrics(registry),
        transactionTemplate(),
        Duration.ofMinutes(5),
        5,
        Duration.ofSeconds(30),
        true);
  }

  /**
   * Executa o callback sem transação de verdade: aqui interessa <b>o que</b> é agrupado
   * atomicamente (estado + evento), não o comportamento do gerenciador de transação, que é
   * exercitado nos testes de integração com Postgres real.
   */
  private static TransactionTemplate transactionTemplate() {
    return new TransactionTemplate(
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
          }

          @Override
          public void commit(TransactionStatus status) {}

          @Override
          public void rollback(TransactionStatus status) {}
        });
  }

  private Assessment pendingAssessment() {
    Assessment a =
        Assessment.submit(
            "default", "11111111-1111-1111-1111-111111111111", DocumentType.CPF, "11144477735",
            "Fulano");
    when(repository.claimPending(anyInt(), any(Duration.class))).thenReturn(List.of(a.id()));
    when(repository.findById(a.id())).thenReturn(Optional.of(a));
    when(identityService.verify(any(VerifyIdentityCommand.class)))
        .thenReturn(
            new IdentityResult(
                IdentityCheck.create("aid", IdentityStatus.VERIFIED, "stub", "ok"), null));
    when(screeningService.screen(any(ScreeningCommand.class)))
        .thenReturn(ScreeningResult.of("aid", List.of()));
    lenient()
        .when(subjectProfileService.find(any(UUID.class), anyString()))
        .thenReturn(completeCpfProfile());
    // Cadastro verificado: o gate de veracidade tem teste próprio, e deixá-lo implícito aqui faria
    // estes casos falharem por um motivo que não é o que eles provam.
    lenient()
        .when(fieldVerificationService.verifiedFields(any(UUID.class), anyString(), any()))
        .thenReturn(
            java.util.Set.of(
                com.barrier.riskengine.subject.profile.domain.VerifiableField.BIRTH_DATE,
                com.barrier.riskengine.subject.profile.domain.VerifiableField.PHONE));
    // Sem verificação registrada: comportamento padrão de quem não usa documentoscopia/biometria.
    lenient()
        .when(assuranceService.latest(any(UUID.class), anyString(), any(AssuranceKind.class)))
        .thenReturn(Optional.empty());
    lenient()
        .when(assuranceService.attempts(any(UUID.class), anyString(), any(AssuranceKind.class)))
        .thenReturn(0L);
    return a;
  }

  /** Cadastro que cobre o checklist mínimo de PF (ver {@code RegistrationCompleteness}). */
  private static SubjectProfile completeCpfProfile() {
    return new SubjectProfile(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "default",
        LocalDate.of(1990, 1, 1),
        null,
        "brasileira",
        "engenheiro",
        null,
        new SubjectProfile.Address("Rua X", "1", null, "Centro", "Cidade", "SP", "00000-000"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        Instant.now(),
        Instant.now());
  }

  private void stubRisk(RiskLevel level, RiskRecommendation recommendation, int score) {
    when(riskScoringService.score(any(RiskContext.class)))
        .thenReturn(new RiskDecision(level, recommendation, score, List.of(), "test/1.0.0"));
  }

  @Test
  void recomendacaoApproveAprovaEPublicaEvento() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    stubRisk(RiskLevel.LOW, RiskRecommendation.APPROVE, 0);

    int processed = processor.process();

    assertThat(processed).isEqualTo(1);
    assertThat(pending.status()).isEqualTo(AssessmentStatus.APROVADO);
    assertThat(pending.riskLevel()).isEqualTo(RiskLevel.LOW);
    verify(repository).save(pending);
    verify(eventPublisher).publishCompleted(pending);
  }

  /**
   * O furo que a verificação fecha: cadastro preenchido com dado plausível e inventado liberava
   * aprovação automática. Agora cai na mesma fila de "falta o campo" — não é reprovação, porque
   * dado não verificado não é dado falso.
   */
  @Test
  void cadastroNaoVerificadoNaoLiberaAprovacaoAutomatica() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    when(fieldVerificationService.verifiedFields(any(UUID.class), anyString(), any()))
        .thenReturn(java.util.Set.of());
    stubRisk(RiskLevel.LOW, RiskRecommendation.APPROVE, 0);

    processor.process();

    assertThat(pending.status()).isEqualTo(AssessmentStatus.SOLICITAR_DOCUMENTO);
    assertThat(pending.factors())
        .anySatisfy(f -> assertThat(f).contains("telefone ou e-mail verificado"));
  }

  @Test
  void recomendacaoReviewVaiParaRevisao() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    stubRisk(RiskLevel.MEDIUM, RiskRecommendation.REVIEW, 300);

    processor.process();

    assertThat(pending.status()).isEqualTo(AssessmentStatus.EM_REVISAO);
    assertThat(pending.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
  }

  @Test
  void recomendacaoRejectReprova() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    stubRisk(RiskLevel.CRITICAL, RiskRecommendation.REJECT, 1000);

    processor.process();

    assertThat(pending.status()).isEqualTo(AssessmentStatus.REPROVADO);
    assertThat(pending.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
  }

  /**
   * Cadastro incompleto sai da fila de EDD: "faltou o endereço" não pede analista, pede o campo.
   * Enquanto isso virava EM_REVISAO, o volume que o time de operações mais via era justamente o que
   * menos precisava dele — e não pode virar REPROVADO, que mentiria na trilha.
   */
  @Test
  void cadastroIncompletoVaiParaSolicitarDocumentoENaoParaRevisao() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    stubRisk(RiskLevel.LOW, RiskRecommendation.APPROVE, 0);
    when(subjectProfileService.find(any(UUID.class), anyString()))
        .thenReturn(SubjectProfile.blank(UUID.randomUUID(), "default"));

    processor.process();

    assertThat(pending.status()).isEqualTo(AssessmentStatus.SOLICITAR_DOCUMENTO);
    assertThat(pending.status())
        .isNotIn(AssessmentStatus.EM_REVISAO, AssessmentStatus.REPROVADO);
    assertThat(pending.factors()).anyMatch(f -> f.contains("Cadastro incompleto"));
  }

  /** Risco que já pedia revisão continua em revisão: o cadastro não rebaixa o que já é EDD. */
  @Test
  void cadastroIncompletoNaoDesviaOQueJaEraRevisao() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    stubRisk(RiskLevel.MEDIUM, RiskRecommendation.REVIEW, 300);
    when(subjectProfileService.find(any(UUID.class), anyString()))
        .thenReturn(SubjectProfile.blank(UUID.randomUUID(), "default"));

    processor.process();

    assertThat(pending.status()).isEqualTo(AssessmentStatus.EM_REVISAO);
  }

  /**
   * Regressão: quando a lease expira e outra réplica conclui a avaliação primeiro, o perdedor da
   * corrida não pode publicar um segundo evento (o cliente receberia dois callbacks, possivelmente
   * contraditórios) nem contabilizar tentativa — a avaliação foi decidida corretamente, só não por
   * este processo.
   */
  @Test
  void perdedorDaCorridaNaoPublicaEventoNemContabilizaFalha() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    stubRisk(RiskLevel.LOW, RiskRecommendation.APPROVE, 0);
    when(repository.save(pending))
        .thenThrow(new OptimisticLockingFailureException("outra réplica concluiu"));

    int processed = processor.process();

    assertThat(processed).isZero();
    verify(eventPublisher, never()).publishCompleted(any());
    assertThat(pending.attempts()).isZero();
  }

  /**
   * A distribuição de desfechos é o único sinal que pega, ao mesmo tempo, regra mal calibrada,
   * provider devolvendo lixo e fraude em escala — e nenhum dos três se anuncia de outro jeito.
   */
  @Test
  void contabilizaODesfechoNaMetrica() {
    var processor = newProcessor();
    pendingAssessment();
    stubRisk(RiskLevel.LOW, RiskRecommendation.APPROVE, 0);

    processor.process();

    assertThat(
            registry
                .counter("barrier.assessment.decisions", "status", "APROVADO", "level", "LOW")
                .count())
        .isEqualTo(1.0);
    assertThat(registry.timer("barrier.assessment.processing").count()).isEqualTo(1L);
  }

  /** Nenhuma tag pode carregar documento, nome ou tenant: métrica não tem controle de acesso. */
  @Test
  void metricaDeDecisaoNaoCarregaDadoPessoal() {
    var processor = newProcessor();
    pendingAssessment();
    stubRisk(RiskLevel.LOW, RiskRecommendation.APPROVE, 0);

    processor.process();

    assertThat(registry.find("barrier.assessment.decisions").counter().getId().getTags())
        .extracting(io.micrometer.core.instrument.Tag::getKey)
        .containsExactlyInAnyOrder("status", "level");
  }

  @Test
  void semPendentesNaoFazNada() {
    var processor = newProcessor();
    when(repository.claimPending(anyInt(), any(Duration.class))).thenReturn(List.of());

    assertThat(processor.process()).isZero();
    verify(eventPublisher, never()).publishCompleted(any());
  }

  /**
   * Regressão do poison pill: antes, uma exceção aqui desfazia o lote inteiro e a avaliação
   * voltava ao topo da fila a cada 2 segundos, indefinidamente.
   */
  @Test
  void falhaDeUmaAvaliacaoViraTentativaContabilizadaSemPublicarEvento() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    when(riskScoringService.score(any(RiskContext.class)))
        .thenThrow(new IllegalStateException("provider quebrado"));

    assertThat(processor.process()).isZero();

    assertThat(pending.status()).isEqualTo(AssessmentStatus.EM_ANALISE);
    assertThat(pending.attempts()).isEqualTo(1);
    assertThat(pending.lastError()).contains("provider quebrado");
    assertThat(pending.nextAttemptAt()).isNotNull();
    verify(eventPublisher, never()).publishCompleted(any());
  }

  /** Esgotadas as tentativas, a avaliação sai do limbo em vez de ser reprocessada para sempre. */
  @Test
  void aposEsgotarTentativasVaiParaFalhaDeProcessamento() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    when(riskScoringService.score(any(RiskContext.class)))
        .thenThrow(new IllegalStateException("provider quebrado"));

    for (int i = 0; i < 5; i++) {
      processor.process();
    }

    assertThat(pending.attempts()).isEqualTo(5);
    assertThat(pending.status()).isEqualTo(AssessmentStatus.FALHA_PROCESSAMENTO);
    assertThat(pending.nextAttemptAt()).isNull();
  }

  /** Reivindicada mas já concluída por outro caminho: não reprocessa. */
  @Test
  void avaliacaoJaConcluidaEIgnorada() {
    var processor = newProcessor();
    Assessment a =
        Assessment.submit(
            "default", UUID.randomUUID().toString(), DocumentType.CPF, "11144477735", "Fulano");
    a.complete(RiskLevel.LOW, AssessmentStatus.APROVADO, "ok", List.of());
    when(repository.claimPending(anyInt(), any(Duration.class))).thenReturn(List.of(a.id()));
    when(repository.findById(a.id())).thenReturn(Optional.of(a));

    assertThat(processor.process()).isZero();
    verify(eventPublisher, never()).publishCompleted(any());
  }

  /**
   * Motor real, com o <b>conjunto de regras</b> do sistema — não só {@code IDENTITY_ASSURANCE}
   * isolada. Um motor de uma regra só não é o cenário que roda em produção: as demais regras
   * também avaliam o mesmo {@code RiskContext} (PJ nula aqui, então a maioria delas não se aplica
   * e não pontua), e é esse conjunto que precisa concordar em {@code APROVADO} quando não há
   * assurance, e discordar (empurrando para {@code EM_REVISAO}) quando há.
   */
  private static RiskScoringService realRiskScoringServiceComTodasAsRegras() {
    RiskRuleRegistryService registryService = mock(RiskRuleRegistryService.class);
    lenient().when(registryService.isActive(anyString())).thenReturn(true);
    RiskScoreRepository riskScoreRepository = mock(RiskScoreRepository.class);
    lenient()
        .when(riskScoreRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TenantRiskConfigService tenantConfig = mock(TenantRiskConfigService.class);
    lenient()
        .when(tenantConfig.getInt(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(invocation -> invocation.getArgument(3));
    lenient()
        .when(tenantConfig.getStringSet(anyString(), anyString(), anyString(), any()))
        .thenAnswer(invocation -> invocation.getArgument(3));

    // Cobertura completa: senão SCREENING_COVERAGE dispararia por si só e o contrafactual
    // "sem assurance = APROVADO" deixaria de provar o que devia provar.
    WatchlistImportStatus watchlistStatus = mock(WatchlistImportStatus.class);
    lenient()
        .when(watchlistStatus.coverage())
        .thenReturn(java.util.Set.of(MatchType.SANCTION, MatchType.PEP));

    List<RiskRule> allRules =
        List.of(
            new IdentityRiskRule(),
            new SanctionRiskRule(),
            new PepRiskRule(),
            new CorporateStructureRiskRule(),
            new DebarmentRiskRule(),
            new NegativeMediaRiskRule(250),
            new ConsistencyRiskRule(60),
            new ScreeningCoverageRiskRule(watchlistStatus),
            new NewCompanyRiskRule(6, 150, Clock.systemUTC(), tenantConfig),
            new SensitiveCnaeRiskRule(java.util.Set.of(), 200, tenantConfig),
            new IdentityAssuranceRiskRule(600, 100, 200, 3, 300));
    return new RiskScoringService(allRules, riskScoreRepository, registryService);
  }

  private AssessmentProcessor processorComMotorReal(RiskScoringService realRiskScoringService) {
    return new AssessmentProcessor(
        repository,
        identityService,
        screeningService,
        realRiskScoringService,
        subjectProfileService,
        fieldVerificationService,
        assuranceService,
        eventPublisher,
        new AssessmentMetrics(registry),
        transactionTemplate(),
        Duration.ofMinutes(5),
        5,
        Duration.ofSeconds(30),
        true);
  }

  private static AssuranceCheck biometricCheck(AssuranceOutcome outcome) {
    return new AssuranceCheck(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "default",
        AssuranceKind.BIOMETRIC,
        outcome,
        10,
        "provedor",
        "ref-1",
        "modelo/2.1",
        "hash",
        "detalhe",
        java.util.Set.of(),
        Instant.now(),
        null);
  }

  /**
   * Prova que a regra dispara <b>pelo pipeline de verdade</b>, não só que o {@code
   * AssuranceSummary} foi montado: usa o {@link RiskScoringService} real, com o conjunto real de
   * regras (não só {@link IdentityAssuranceRiskRule}), sem mock no meio. Biometria com {@code
   * FAIL} muda a decisão para o mesmo subject que, sem assurance, sairia aprovado — é o par com
   * {@link #semAssuranceOMesmoSubjectSaiAprovadoPeloMotorDeVerdade()}, e só os dois juntos provam
   * que foi o insumo de assurance que mudou a decisão, não outra coisa no cenário.
   */
  @Test
  void biometriaComFalhaMudaADecisaoPeloMotorDeVerdade() {
    var processor = processorComMotorReal(realRiskScoringServiceComTodasAsRegras());
    Assessment pending = pendingAssessment();
    when(assuranceService.latest(any(UUID.class), anyString(), eq(AssuranceKind.BIOMETRIC)))
        .thenReturn(Optional.of(biometricCheck(AssuranceOutcome.FAIL)));

    processor.process();

    assertThat(pending.status()).isEqualTo(AssessmentStatus.EM_REVISAO);
    assertThat(pending.factors()).anyMatch(f -> f.contains("IDENTITY_ASSURANCE"));
  }

  /**
   * O contrafactual: mesmo subject, mesmo motor (conjunto completo de regras), sem nenhuma
   * verificação de assurance registrada — sai {@code APROVADO}. Sem este teste, "biometria FAIL
   * mudou a decisão" ficava só no Javadoc do teste acima, nunca provado em código: o par é o que
   * garante que a mudança de status veio do assurance, não de outro fator do cenário.
   */
  @Test
  void semAssuranceOMesmoSubjectSaiAprovadoPeloMotorDeVerdade() {
    var processor = processorComMotorReal(realRiskScoringServiceComTodasAsRegras());
    Assessment pending = pendingAssessment();

    processor.process();

    assertThat(pending.status()).isEqualTo(AssessmentStatus.APROVADO);
  }
}
