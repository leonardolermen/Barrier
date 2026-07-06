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
  @Mock OutboxRecorder outbox;

  private static ObjectMapper objectMapper() {
    // Jackson 3: suporte a java.time é embutido no databind.
    return JsonMapper.builder().build();
  }

  @Test
  void concluiPendenteEGravaEventoNaOutbox() {
    var processor = new AssessmentProcessor(repository, outbox, objectMapper());
    Assessment pending = Assessment.submit(DocumentType.CPF, "11144477735", "Fulano");
    when(repository.findPending(anyInt())).thenReturn(List.of(pending));

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
  void semPendentesNaoFazNada() {
    var processor = new AssessmentProcessor(repository, outbox, objectMapper());
    when(repository.findPending(anyInt())).thenReturn(List.of());

    assertThat(processor.process()).isZero();
    verify(outbox, org.mockito.Mockito.never()).record(any(), any(), anyInt(), any());
  }
}
