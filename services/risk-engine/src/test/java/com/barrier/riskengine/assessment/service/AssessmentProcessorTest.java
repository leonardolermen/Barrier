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
  @Mock OutboxRecorder outbox;

  private static ObjectMapper objectMapper() {
    // Jackson 3: suporte a java.time é embutido no databind.
    return JsonMapper.builder().build();
  }

  private AssessmentProcessor newProcessor() {
    return new AssessmentProcessor(repository, identityService, outbox, objectMapper());
  }

  private void stubIdentity(IdentityStatus status) {
    when(identityService.verify(any(VerifyIdentityCommand.class)))
        .thenReturn(IdentityCheck.create("aid", status, "stub", "detalhe"));
  }

  @Test
  void identidadeVerificadaAprovaEGravaEvento() {
    var processor = newProcessor();
    Assessment pending = Assessment.submit(DocumentType.CPF, "11144477735", "Fulano");
    when(repository.findPending(anyInt())).thenReturn(List.of(pending));
    stubIdentity(IdentityStatus.VERIFIED);

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
  void identidadeNaoEncontradaReprova() {
    var processor = newProcessor();
    Assessment pending = Assessment.submit(DocumentType.CPF, "11144477735", "Fulano");
    when(repository.findPending(anyInt())).thenReturn(List.of(pending));
    stubIdentity(IdentityStatus.NOT_FOUND);

    processor.process();

    assertThat(pending.status()).isEqualTo(AssessmentStatus.REPROVADO);
    assertThat(pending.riskLevel()).isEqualTo(RiskLevel.ALTO);
  }

  @Test
  void bureauIndisponivelNaoReprova() {
    var processor = newProcessor();
    Assessment pending = Assessment.submit(DocumentType.CPF, "11144477735", "Fulano");
    when(repository.findPending(anyInt())).thenReturn(List.of(pending));
    stubIdentity(IdentityStatus.UNAVAILABLE);

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
