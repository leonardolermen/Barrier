package com.barrier.riskengine.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentId;
import com.barrier.riskengine.assessment.domain.AssessmentNotFoundException;
import com.barrier.riskengine.assessment.domain.AssessmentStatus;
import com.barrier.riskengine.assessment.domain.DocumentType;
import com.barrier.riskengine.assessment.domain.InvalidDocumentException;
import com.barrier.riskengine.assessment.repository.AssessmentRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssessmentServiceTest {

  @Mock AssessmentRepository repository;

  @Test
  void submitCriaAvaliacaoEmAnalise() {
    var service = new AssessmentService(repository);
    when(repository.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> inv.getArgument(0));

    Assessment result =
        service.submit(
            new SubmitAssessmentCommand("default", DocumentType.CPF, "111.444.777-35", "Fulano"));

    assertThat(result.status()).isEqualTo(AssessmentStatus.EM_ANALISE);
    assertThat(result.documentDigits()).isEqualTo("11144477735");
    assertThat(result.tenantId()).isEqualTo("default");
  }

  @Test
  void submitRejeitaDocumentoInvalido() {
    var service = new AssessmentService(repository);

    assertThatThrownBy(
            () ->
                service.submit(
                    new SubmitAssessmentCommand(
                        "default", DocumentType.CPF, "00000000000", "Fulano")))
        .isInstanceOf(InvalidDocumentException.class);
  }

  @Test
  void getLancaQuandoNaoEncontrado() {
    var service = new AssessmentService(repository);
    var id = AssessmentId.newId();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(id, "default"))
        .isInstanceOf(AssessmentNotFoundException.class);
  }

  @Test
  void getDeOutroTenantNaoEncontra() {
    var service = new AssessmentService(repository);
    var assessment = Assessment.submit("acme", DocumentType.CPF, "111.444.777-35", "Fulano");
    when(repository.findById(assessment.id())).thenReturn(Optional.of(assessment));

    assertThatThrownBy(() -> service.get(assessment.id(), "outro-tenant"))
        .isInstanceOf(AssessmentNotFoundException.class);
  }
}
