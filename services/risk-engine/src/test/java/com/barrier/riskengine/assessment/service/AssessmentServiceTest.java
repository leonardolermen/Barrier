package com.barrier.riskengine.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentId;
import com.barrier.riskengine.assessment.domain.AssessmentNotFoundException;
import com.barrier.riskengine.assessment.domain.AssessmentStatus;
import com.barrier.riskengine.assessment.domain.DocumentType;
import com.barrier.riskengine.assessment.domain.InvalidDocumentException;
import com.barrier.riskengine.assessment.repository.AssessmentRepository;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssessmentServiceTest {

  @Mock AssessmentRepository repository;
  @Mock SubjectService subjectService;
  @Mock AssessmentEventPublisher eventPublisher;

  private AssessmentService service() {
    return new AssessmentService(repository, subjectService, eventPublisher);
  }

  private Assessment emRevisao(String tenantId) {
    Assessment a =
        Assessment.submit(
            tenantId, UUID.randomUUID().toString(), DocumentType.CPF, "111.444.777-35", "Fulano");
    a.complete(RiskLevel.MEDIUM, AssessmentStatus.EM_REVISAO, "revisar", List.of());
    return a;
  }

  @Test
  void submitAchaSubjectVinculaECriaAvaliacao() {
    when(subjectService.findOrCreate(any(), any(), any()))
        .thenReturn(Subject.create("CPF", "11144477735", "Fulano"));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Assessment result =
        service()
            .submit(
                new SubmitAssessmentCommand("default", DocumentType.CPF, "111.444.777-35", "Fulano"));

    assertThat(result.status()).isEqualTo(AssessmentStatus.EM_ANALISE);
    assertThat(result.tenantId()).isEqualTo("default");
    assertThat(result.subjectId()).isNotNull();
  }

  @Test
  void submitRejeitaDocumentoInvalidoAntesDoSubject() {
    assertThatThrownBy(
            () ->
                service()
                    .submit(
                        new SubmitAssessmentCommand(
                            "default", DocumentType.CPF, "00000000000", "Fulano")))
        .isInstanceOf(InvalidDocumentException.class);
  }

  @Test
  void getLancaQuandoNaoEncontrado() {
    var id = AssessmentId.newId();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().get(id, "default"))
        .isInstanceOf(AssessmentNotFoundException.class);
  }

  @Test
  void getDeOutroTenantNaoEncontra() {
    var assessment =
        Assessment.submit(
            "acme", UUID.randomUUID().toString(), DocumentType.CPF, "111.444.777-35", "Fulano");
    when(repository.findById(assessment.id())).thenReturn(Optional.of(assessment));

    assertThatThrownBy(() -> service().get(assessment.id(), "outro-tenant"))
        .isInstanceOf(AssessmentNotFoundException.class);
  }

  @Test
  void decideAprovaEPublicaEvento() {
    Assessment a = emRevisao("default");
    when(repository.findById(a.id())).thenReturn(Optional.of(a));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Assessment result = service().decide(a.id(), "default", true, "analista@empresa", "ok");

    assertThat(result.status()).isEqualTo(AssessmentStatus.APROVADO);
    assertThat(result.reviewedBy()).isEqualTo("analista@empresa");
    verify(eventPublisher).publishCompleted(result);
  }

  @Test
  void decideDeOutroTenantNaoEncontra() {
    Assessment a = emRevisao("empresa-1");
    when(repository.findById(a.id())).thenReturn(Optional.of(a));

    assertThatThrownBy(() -> service().decide(a.id(), "empresa-2", true, "x", null))
        .isInstanceOf(AssessmentNotFoundException.class);
  }

  @Test
  void decideDeAvaliacaoNaoEmRevisaoConflita() {
    var a =
        Assessment.submit(
            "default", UUID.randomUUID().toString(), DocumentType.CPF, "111.444.777-35", "Fulano");
    when(repository.findById(a.id())).thenReturn(Optional.of(a));

    assertThatThrownBy(() -> service().decide(a.id(), "default", true, "x", null))
        .isInstanceOf(IllegalStateException.class);
  }
}
