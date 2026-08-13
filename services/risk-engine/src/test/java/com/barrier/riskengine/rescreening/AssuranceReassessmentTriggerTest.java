package com.barrier.riskengine.rescreening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.barrier.riskengine.subject.domain.SubjectNotFoundException;
import com.barrier.riskengine.subject.service.SubjectService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssuranceReassessmentTriggerTest {

  private static final UUID SUBJECT_ID = UUID.randomUUID();
  private static final String TENANT = "tenant-1";

  @Mock AssessmentService assessments;
  @Mock SubjectService subjects;

  private AssuranceReassessmentTrigger trigger;

  @BeforeEach
  void setUp() {
    trigger = new AssuranceReassessmentTrigger(assessments, subjects);
  }

  private AssuranceCheck check(AssuranceOutcome outcome) {
    return check(outcome, TENANT);
  }

  private AssuranceCheck check(AssuranceOutcome outcome, String tenantId) {
    return new AssuranceCheck(
        UUID.randomUUID(),
        SUBJECT_ID,
        tenantId,
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
    when(subjects.findById(SUBJECT_ID, TENANT)).thenReturn(subject());

    trigger.onRecorded(check(AssuranceOutcome.PASS));

    ArgumentCaptor<SubmitAssessmentCommand> cmd =
        ArgumentCaptor.forClass(SubmitAssessmentCommand.class);
    verify(assessments).submit(cmd.capture());
    assertThat(cmd.getValue().origin()).isEqualTo(AssessmentOrigin.ASSURANCE);
    assertThat(cmd.getValue().originDetail()).isEqualTo("DOCUMENT@abc-123");
    assertThat(cmd.getValue().tenantId()).isEqualTo(TENANT);
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
    when(subjects.findById(SUBJECT_ID, TENANT)).thenReturn(subject());

    trigger.onRecorded(check(AssuranceOutcome.FAIL));

    verify(assessments).submit(any());
  }

  @Test
  void reavaliaEmInconclusiveEUnavailable() {
    when(subjects.findById(SUBJECT_ID, TENANT)).thenReturn(subject());

    trigger.onRecorded(check(AssuranceOutcome.INCONCLUSIVE));
    trigger.onRecorded(check(AssuranceOutcome.UNAVAILABLE));

    verify(assessments, times(2)).submit(any());
  }

  /**
   * Um {@code AssuranceCheck} de um tenant não pode resolver o subject de outro. Sem o escopo por
   * tenant, o trigger criaria o vínculo tenant↔subject via {@code AssessmentService.submit} e
   * produziria um {@code Assessment} carregando documento e nome de cliente que aquele parceiro
   * nunca viu. O cenário só faz sentido depois do escopo: com um único tenant no teste, um trigger
   * que pegasse o tenant de qualquer lugar passaria do mesmo jeito.
   */
  @Test
  void naoReavaliaSubjectDeOutroTenant() {
    when(subjects.findById(SUBJECT_ID, "tenant-A"))
        .thenThrow(new SubjectNotFoundException("Subject não encontrado: " + SUBJECT_ID));

    assertThatCode(() -> trigger.onRecorded(check(AssuranceOutcome.PASS, "tenant-A")))
        .doesNotThrowAnyException();

    verifyNoInteractions(assessments);
  }

  /**
   * A garantia de "nunca lança" do Javadoc de {@code onRecorded} precisa de teste próprio: quem
   * chama é o {@code AssuranceService} depois do commit, e uma falha aqui não pode subir e
   * interromper a notificação dos outros listeners.
   */
  @Test
  void naoLancaQuandoResolverOSubjectFalha() {
    when(subjects.findById(SUBJECT_ID, TENANT)).thenThrow(new RuntimeException("banco fora"));

    assertThatCode(() -> trigger.onRecorded(check(AssuranceOutcome.PASS)))
        .doesNotThrowAnyException();

    verify(assessments, never()).submit(any());
  }

  @Test
  void naoLancaQuandoSubmeterAReavaliacaoFalha() {
    when(subjects.findById(SUBJECT_ID, TENANT)).thenReturn(subject());
    when(assessments.submit(any())).thenThrow(new RuntimeException("bureau fora"));

    assertThatCode(() -> trigger.onRecorded(check(AssuranceOutcome.PASS)))
        .doesNotThrowAnyException();
  }

  /**
   * {@code Subject.documentType()} é {@code String}; um valor fora do enum {@code DocumentType}
   * é erro de dado, não indisponibilidade do reavaliador — mas mesmo assim não pode escapar de
   * {@code onRecorded} (mesma garantia de "nunca lança" dos testes acima).
   */
  @Test
  void naoLancaQuandoDocumentTypeDoSubjectEInvalido() {
    Subject subjectInvalido =
        new Subject(SUBJECT_ID, "XPTO", "11144477735", "Fulano de Tal", Instant.now());
    when(subjects.findById(SUBJECT_ID, TENANT)).thenReturn(subjectInvalido);

    assertThatCode(() -> trigger.onRecorded(check(AssuranceOutcome.PASS)))
        .doesNotThrowAnyException();

    verifyNoInteractions(assessments);
  }
}
