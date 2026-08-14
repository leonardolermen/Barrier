package com.barrier.riskengine.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentId;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentOrigin;
import com.barrier.riskengine.assessment.domain.exceptions.AssessmentNotFoundException;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.domain.exceptions.IdempotencyConflictException;
import com.barrier.riskengine.assessment.domain.IdempotencyReservation;
import com.barrier.riskengine.assessment.domain.exceptions.InvalidDocumentException;
import com.barrier.riskengine.assessment.repository.interfaces.AssessmentRepository;
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
  @Mock IdempotencyService idempotency;

  private AssessmentService service() {
    return new AssessmentService(repository, subjectService, eventPublisher, idempotency);
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

    SubmissionResult result =
        service()
            .submit(
                new SubmitAssessmentCommand("default", DocumentType.CPF, "111.444.777-35", "Fulano"));

    assertThat(result.replayed()).isFalse();
    assertThat(result.assessment().status()).isEqualTo(AssessmentStatus.EM_ANALISE);
    assertThat(result.assessment().tenantId()).isEqualTo("default");
    assertThat(result.assessment().subjectId()).isNotNull();
    // sem Idempotency-Key o serviço nem consulta a tabela de chaves
    verifyNoInteractions(idempotency);
  }

  @Test
  void submitComOrigemAssuranceGravaOriginEOriginDetail() {
    when(subjectService.findOrCreate(any(), any(), any()))
        .thenReturn(Subject.create("CPF", "11144477735", "Fulano"));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    SubmissionResult result =
        service()
            .submit(
                SubmitAssessmentCommand.assurance(
                    "default",
                    DocumentType.CPF,
                    "111.444.777-35",
                    "Fulano",
                    "DOCUMENT@abc-123"));

    assertThat(result.assessment().origin()).isEqualTo(AssessmentOrigin.ASSURANCE);
    assertThat(result.assessment().originDetail()).isEqualTo("DOCUMENT@abc-123");
  }

  @Test
  void submitComChaveRepetidaDevolveAAvaliacaoOriginalSemCriarOutra() {
    var original =
        Assessment.submit(
            "default", UUID.randomUUID().toString(), DocumentType.CPF, "111.444.777-35", "Fulano");
    var command =
        new SubmitAssessmentCommand(
            "default", DocumentType.CPF, "111.444.777-35", "Fulano", "chave-1");
    when(idempotency.reserve(eq("default"), eq("chave-1"), any()))
        .thenAnswer(
            inv ->
                new IdempotencyReservation(inv.getArgument(2), original.id(), false));
    when(repository.findById(original.id())).thenReturn(Optional.of(original));

    SubmissionResult result = service().submit(command);

    assertThat(result.replayed()).isTrue();
    assertThat(result.assessment().id()).isEqualTo(original.id());
    verify(repository, never()).save(any());
    verifyNoInteractions(subjectService);
  }

  /** Mesma chave com outro conteúdo é erro do cliente: servir a resposta antiga seria mentir. */
  @Test
  void submitComChaveReusadaParaOutroConteudoConflita() {
    when(idempotency.reserve(eq("default"), eq("chave-1"), any()))
        .thenReturn(new IdempotencyReservation("hash-de-outra-requisicao", AssessmentId.newId(), false));

    assertThatThrownBy(
            () ->
                service()
                    .submit(
                        new SubmitAssessmentCommand(
                            "default", DocumentType.CPF, "111.444.777-35", "Fulano", "chave-1")))
        .isInstanceOf(IdempotencyConflictException.class);
    verify(repository, never()).save(any());
  }

  /** Reserva sem avaliação: a submissão original ainda está em curso, não há o que repetir. */
  @Test
  void submitComSubmissaoOriginalEmAndamentoConflita() {
    var command =
        new SubmitAssessmentCommand(
            "default", DocumentType.CPF, "111.444.777-35", "Fulano", "chave-1");
    when(idempotency.reserve(eq("default"), eq("chave-1"), any()))
        .thenAnswer(inv -> new IdempotencyReservation(inv.getArgument(2), null, false));

    assertThatThrownBy(() -> service().submit(command))
        .isInstanceOf(IdempotencyConflictException.class);
  }

  /** Falha depois da reserva não pode deixar a chave travada até o fim da janela. */
  @Test
  void submitQueFalhaLiberaAChave() {
    when(idempotency.reserve(eq("default"), eq("chave-1"), any()))
        .thenAnswer(inv -> IdempotencyReservation.taken(inv.getArgument(2)));
    when(subjectService.findOrCreate(any(), any(), any()))
        .thenThrow(new IllegalStateException("banco fora do ar"));

    assertThatThrownBy(
            () ->
                service()
                    .submit(
                        new SubmitAssessmentCommand(
                            "default", DocumentType.CPF, "111.444.777-35", "Fulano", "chave-1")))
        .isInstanceOf(IllegalStateException.class);

    verify(idempotency).release("default", "chave-1");
    verify(idempotency, never()).bind(any(), any(), any());
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

    Assessment result = service().decide(a.id(), "default", true, "analista@empresa", "chave-teste", "ok");

    assertThat(result.status()).isEqualTo(AssessmentStatus.APROVADO);
    assertThat(result.reviewedBy()).isEqualTo("analista@empresa");
    assertThat(result.reviewedByKey()).isEqualTo("chave-teste");
    verify(eventPublisher).publishCompleted(result);
  }

  @Test
  void decideDeOutroTenantNaoEncontra() {
    Assessment a = emRevisao("empresa-1");
    when(repository.findById(a.id())).thenReturn(Optional.of(a));

    assertThatThrownBy(() -> service().decide(a.id(), "empresa-2", true, "x", "chave-teste", null))
        .isInstanceOf(AssessmentNotFoundException.class);
  }

  @Test
  void decideDeAvaliacaoNaoEmRevisaoConflita() {
    var a =
        Assessment.submit(
            "default", UUID.randomUUID().toString(), DocumentType.CPF, "111.444.777-35", "Fulano");
    when(repository.findById(a.id())).thenReturn(Optional.of(a));

    assertThatThrownBy(() -> service().decide(a.id(), "default", true, "x", "chave-teste", null))
        .isInstanceOf(IllegalStateException.class);
  }
}
