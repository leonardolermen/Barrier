package com.barrier.riskengine.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.commons.outbox.OutboxRecorder;
import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentStatus;
import com.barrier.riskengine.assessment.domain.DocumentType;
import com.barrier.riskengine.assessment.repository.AssessmentRepository;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class AssessmentProcessorTest {

  @Mock AssessmentRepository repository;
  @Mock IdentityService identityService;
  @Mock ScreeningService screeningService;
  @Mock RiskScoringService riskScoringService;
  @Mock OutboxRecorder outbox;

  private static ObjectMapper objectMapper() {
    return JsonMapper.builder().build();
  }

  private AssessmentProcessor newProcessor() {
    return new AssessmentProcessor(
        repository, identityService, screeningService, riskScoringService, outbox, objectMapper());
  }

  private Assessment pendingAssessment() {
    Assessment a =
        Assessment.submit(
            "default", "11111111-1111-1111-1111-111111111111", DocumentType.CPF, "11144477735",
            "Fulano");
    when(repository.findPending(anyInt())).thenReturn(List.of(a));
    when(identityService.verify(any(VerifyIdentityCommand.class)))
        .thenReturn(IdentityCheck.create("aid", IdentityStatus.VERIFIED, "stub", "ok"));
    when(screeningService.screen(any(ScreeningCommand.class)))
        .thenReturn(ScreeningResult.of("aid", List.of()));
    return a;
  }

  private void stubRisk(RiskLevel level, RiskRecommendation recommendation, int score) {
    when(riskScoringService.score(any(RiskContext.class)))
        .thenReturn(new RiskDecision(level, recommendation, score, List.of(), "test/1.0.0"));
  }

  @Test
  void recomendacaoApproveAprovaEGravaEvento() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    stubRisk(RiskLevel.LOW, RiskRecommendation.APPROVE, 0);

    int processed = processor.process();

    assertThat(processed).isEqualTo(1);
    assertThat(pending.status()).isEqualTo(AssessmentStatus.APROVADO);
    assertThat(pending.riskLevel()).isEqualTo(RiskLevel.LOW);
    verify(repository).save(pending);

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(outbox)
        .record(
            eq(pending.id().asString()),
            eq("barrier.assessment.completed"),
            eq(1),
            payload.capture());
    assertThat(payload.getValue()).contains("APROVADO").contains("LOW");
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
  void semPendentesNaoFazNada() {
    var processor = newProcessor();
    when(repository.findPending(anyInt())).thenReturn(List.of());

    assertThat(processor.process()).isZero();
    verify(outbox, org.mockito.Mockito.never()).record(any(), any(), anyInt(), any());
  }
}
