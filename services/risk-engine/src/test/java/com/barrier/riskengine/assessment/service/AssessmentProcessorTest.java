package com.barrier.riskengine.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentStatus;
import com.barrier.riskengine.assessment.domain.DocumentType;
import com.barrier.riskengine.assessment.repository.AssessmentRepository;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.identity.service.IdentityResult;
import com.barrier.riskengine.identity.service.IdentityService;
import com.barrier.riskengine.identity.service.VerifyIdentityCommand;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskDecision;
import com.barrier.riskengine.risk.rule.RiskContext;
import com.barrier.riskengine.risk.service.RiskScoringService;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.screening.service.ScreeningCommand;
import com.barrier.riskengine.screening.service.ScreeningService;
import com.barrier.riskengine.subject.profile.domain.RegistrationCompleteness;
import com.barrier.riskengine.subject.profile.service.SubjectProfileService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssessmentProcessorTest {

  @Mock AssessmentRepository repository;
  @Mock IdentityService identityService;
  @Mock ScreeningService screeningService;
  @Mock RiskScoringService riskScoringService;
  @Mock SubjectProfileService subjectProfileService;
  @Mock AssessmentEventPublisher eventPublisher;

  private AssessmentProcessor newProcessor() {
    return new AssessmentProcessor(
        repository,
        identityService,
        screeningService,
        riskScoringService,
        subjectProfileService,
        eventPublisher);
  }

  private Assessment pendingAssessment() {
    Assessment a =
        Assessment.submit(
            "default", "11111111-1111-1111-1111-111111111111", DocumentType.CPF, "11144477735",
            "Fulano");
    when(repository.findPending(anyInt())).thenReturn(List.of(a));
    when(identityService.verify(any(VerifyIdentityCommand.class)))
        .thenReturn(
            new IdentityResult(
                IdentityCheck.create("aid", IdentityStatus.VERIFIED, "stub", "ok"), null));
    when(screeningService.screen(any(ScreeningCommand.class)))
        .thenReturn(ScreeningResult.of("aid", List.of()));
    lenient()
        .when(subjectProfileService.completeness(any(UUID.class), any(String.class)))
        .thenReturn(new RegistrationCompleteness(true, List.of()));
    return a;
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

  @Test
  void cadastroIncompletoRebaixaAprovadoParaRevisao() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    stubRisk(RiskLevel.LOW, RiskRecommendation.APPROVE, 0);
    when(subjectProfileService.completeness(any(UUID.class), any(String.class)))
        .thenReturn(new RegistrationCompleteness(false, List.of("endereço")));

    processor.process();

    assertThat(pending.status()).isEqualTo(AssessmentStatus.EM_REVISAO);
    assertThat(pending.factors()).anyMatch(f -> f.contains("Cadastro incompleto"));
  }

  @Test
  void semPendentesNaoFazNada() {
    var processor = newProcessor();
    when(repository.findPending(anyInt())).thenReturn(List.of());

    assertThat(processor.process()).isZero();
    verify(eventPublisher, never()).publishCompleted(any());
  }
}
