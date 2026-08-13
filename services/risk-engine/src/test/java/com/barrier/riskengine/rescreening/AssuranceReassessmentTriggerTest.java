package com.barrier.riskengine.rescreening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentOrigin;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.assessment.service.SubmitAssessmentCommand;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.rescreening.service.AssuranceReassessmentTrigger;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssuranceReassessmentTriggerTest {

  private static final UUID SUBJECT_ID = UUID.randomUUID();

  @Mock AssessmentService assessments;
  @Mock SubjectService subjects;

  private AssuranceReassessmentTrigger trigger() {
    return new AssuranceReassessmentTrigger(assessments, subjects);
  }

  private AssuranceCheck check(AssuranceOutcome outcome) {
    return new AssuranceCheck(
        UUID.randomUUID(),
        SUBJECT_ID,
        "tenant-1",
        AssuranceKind.DOCUMENT,
        outcome,
        90,
        "stub",
        "abc-123",
        "v1",
        "hash",
        "ok",
        Instant.now(),
        null);
  }

  private Subject subject() {
    return new Subject(SUBJECT_ID, "CPF", "11144477735", "Fulano de Tal", Instant.now());
  }

  @Test
  void submeteReavaliacaoComOriginAssurance() {
    when(subjects.findById(SUBJECT_ID)).thenReturn(subject());

    trigger().onRecorded(check(AssuranceOutcome.PASS));

    ArgumentCaptor<SubmitAssessmentCommand> cmd =
        ArgumentCaptor.forClass(SubmitAssessmentCommand.class);
    verify(assessments).submit(cmd.capture());
    assertThat(cmd.getValue().origin()).isEqualTo(AssessmentOrigin.ASSURANCE);
    assertThat(cmd.getValue().originDetail()).isEqualTo("DOCUMENT@abc-123");
    assertThat(cmd.getValue().tenantId()).isEqualTo("tenant-1");
    assertThat(cmd.getValue().documentType()).isEqualTo(DocumentType.CPF);
    assertThat(cmd.getValue().document()).isEqualTo("11144477735");
    assertThat(cmd.getValue().name()).isEqualTo("Fulano de Tal");
    assertThat(cmd.getValue().hasIdempotencyKey()).isFalse();
  }

  /**
   * Prova de vida que falhou é o insumo que mais muda a decisão: um caminho que só reavaliasse no
   * PASS deixaria a avaliação parada exatamente no caso de fraude.
   */
  @Test
  void reavaliaMesmoEmFail() {
    when(subjects.findById(SUBJECT_ID)).thenReturn(subject());

    trigger().onRecorded(check(AssuranceOutcome.FAIL));

    verify(assessments).submit(any());
  }

  @Test
  void reavaliaEmInconclusiveEUnavailable() {
    when(subjects.findById(SUBJECT_ID)).thenReturn(subject());

    trigger().onRecorded(check(AssuranceOutcome.INCONCLUSIVE));
    trigger().onRecorded(check(AssuranceOutcome.UNAVAILABLE));

    org.mockito.Mockito.verify(assessments, org.mockito.Mockito.times(2)).submit(any());
  }
}
