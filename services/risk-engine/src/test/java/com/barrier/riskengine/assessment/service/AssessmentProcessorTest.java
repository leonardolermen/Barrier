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
import com.barrier.riskengine.assessment.domain.RiskLevel;
import com.barrier.riskengine.assessment.repository.AssessmentRepository;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.identity.service.IdentityService;
import com.barrier.riskengine.identity.service.VerifyIdentityCommand;
import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.ScreeningHit;
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
  @Mock OutboxRecorder outbox;

  private static ObjectMapper objectMapper() {
    return JsonMapper.builder().build();
  }

  private AssessmentProcessor newProcessor() {
    return new AssessmentProcessor(
        repository, identityService, screeningService, outbox, objectMapper());
  }

  private Assessment pendingAssessment() {
    Assessment a = Assessment.submit(DocumentType.CPF, "11144477735", "Fulano");
    when(repository.findPending(anyInt())).thenReturn(List.of(a));
    return a;
  }

  private void stubIdentity(IdentityStatus status) {
    when(identityService.verify(any(VerifyIdentityCommand.class)))
        .thenReturn(IdentityCheck.create("aid", status, "stub", "detalhe"));
  }

  private void stubScreening(boolean hasHits) {
    List<ScreeningHit> hits =
        hasHits
            ? List.of(new ScreeningHit(MatchType.SANCTION, "OFAC", "X", "SDN"))
            : List.of();
    when(screeningService.screen(any(ScreeningCommand.class)))
        .thenReturn(ScreeningResult.of("aid", hits));
  }

  @Test
  void identidadeVerificadaSemHitAprovaEGravaEvento() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    stubIdentity(IdentityStatus.VERIFIED);
    stubScreening(false);

    int processed = processor.process();

    assertThat(processed).isEqualTo(1);
    assertThat(pending.status()).isEqualTo(AssessmentStatus.APROVADO);
    assertThat(pending.riskLevel()).isEqualTo(RiskLevel.BAIXO);
    verify(repository).save(pending);

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(outbox)
        .record(
            eq(pending.id().asString()),
            eq("barrier.assessment.completed"),
            eq(1),
            payload.capture());
    assertThat(payload.getValue()).contains("APROVADO").contains("BAIXO");
  }

  @Test
  void screeningComApontamentoVaiParaRevisao() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    stubIdentity(IdentityStatus.VERIFIED);
    stubScreening(true);

    processor.process();

    assertThat(pending.status()).isEqualTo(AssessmentStatus.EM_REVISAO);
    assertThat(pending.riskLevel()).isEqualTo(RiskLevel.ALTO);
  }

  @Test
  void identidadeNaoEncontradaReprovaSemScreening() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    stubIdentity(IdentityStatus.NOT_FOUND);

    processor.process();

    assertThat(pending.status()).isEqualTo(AssessmentStatus.REPROVADO);
    assertThat(pending.riskLevel()).isEqualTo(RiskLevel.ALTO);
  }

  @Test
  void bureauIndisponivelSegueParaScreening() {
    var processor = newProcessor();
    Assessment pending = pendingAssessment();
    stubIdentity(IdentityStatus.UNAVAILABLE);
    stubScreening(false);

    processor.process();

    assertThat(pending.status()).isEqualTo(AssessmentStatus.APROVADO);
  }

  @Test
  void semPendentesNaoFazNada() {
    var processor = newProcessor();
    when(repository.findPending(anyInt())).thenReturn(List.of());

    assertThat(processor.process()).isZero();
    verify(outbox, org.mockito.Mockito.never()).record(any(), any(), anyInt(), any());
  }
}
