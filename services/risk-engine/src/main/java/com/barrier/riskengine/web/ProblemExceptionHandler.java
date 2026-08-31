package com.barrier.riskengine.web;

import com.barrier.riskengine.assessment.domain.exceptions.AssessmentNotFoundException;
import com.barrier.riskengine.assessment.domain.exceptions.IdempotencyConflictException;
import com.barrier.riskengine.assessment.domain.exceptions.InvalidDocumentException;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentStateException;
import com.barrier.riskengine.assurance.domain.AssuranceDisabledException;
import com.barrier.riskengine.assurance.domain.DocumentGateNotSatisfiedException;
import com.barrier.riskengine.replay.domain.DecisionNotReplayableException;
import com.barrier.riskengine.subject.domain.SubjectNotFoundException;
import com.barrier.riskengine.tenant.domain.UnknownTenantException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Traduz exceções para respostas {@code application/problem+json} (RFC 7807). */
@RestControllerAdvice
class ProblemExceptionHandler {

  @ExceptionHandler({AssessmentNotFoundException.class, SubjectNotFoundException.class})
  ProblemDetail handleNotFound(RuntimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler({InvalidDocumentException.class, IllegalArgumentException.class})
  ProblemDetail handleBadRequest(RuntimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(UnknownTenantException.class)
  ProblemDetail handleUnknownTenant(UnknownTenantException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
  }

  /**
   * Reuso indevido de {@code Idempotency-Key} (conteúdo diferente) ou submissão original ainda em
   * curso. 409 e não 500: o cliente precisa distinguir "sua chave já vale para outra coisa" de um
   * erro do servidor — no primeiro caso, repetir não resolve.
   */
  @ExceptionHandler(IdempotencyConflictException.class)
  ProblemDetail handleIdempotencyConflict(IdempotencyConflictException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  /**
   * Conflitos de estado do domínio: 409 com o motivo, porque o chamador precisa saber o que
   * aconteceu — dois revisores decidindo o mesmo caso é cenário real.
   *
   * <p>Antes isto era {@code @ExceptionHandler(IllegalStateException.class)}, e por isso devolvia
   * também a mensagem de qualquer erro interno que usasse a exceção genérica da plataforma. Foi
   * assim que um chamador <b>não autenticado</b> recebeu <i>"Rota declara AuthenticatedTenant mas
   * não está coberta pelo TenantAuthenticationFilter"</i>. Sem o handler genérico, esses casos
   * viram 500 sem detalhe, que é o correto para erro de programação — ver
   * {@code ProblemExceptionHandlerTest}.
   */
  @ExceptionHandler({
    AssessmentStateException.class,
    AssuranceDisabledException.class,
    DecisionNotReplayableException.class
  })
  ProblemDetail handleConflict(RuntimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  /**
   * Biometria acionada sem documentoscopia {@code PASS} para o mesmo {@code (subjectId,
   * tenantId)}. 409, mesma semântica do kill switch: pré-condição de estado não satisfeita, não
   * erro de entrada — mas exceção própria, não {@code IllegalStateException}, porque o parceiro
   * precisa distinguir as duas causas.
   */
  @ExceptionHandler(DocumentGateNotSatisfiedException.class)
  ProblemDetail handleDocumentGateNotSatisfied(DocumentGateNotSatisfiedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  /**
   * Dois revisores decidindo a mesma avaliação ao mesmo tempo: o segundo recebe 409, não 500. O
   * conflito é uma resposta legítima da API — a decisão do outro já valeu —, e devolver erro de
   * servidor levaria o cliente a tentar de novo, que é exatamente o que não deve fazer.
   */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  ProblemDetail handleConcurrentModification(OptimisticLockingFailureException e) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT, "Avaliação alterada por outro processo; recarregue e tente novamente");
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail handleValidation(MethodArgumentNotValidException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Requisição inválida");
    var fieldError = e.getBindingResult().getFieldError();
    if (fieldError != null) {
      problem.setDetail(fieldError.getField() + ": " + fieldError.getDefaultMessage());
    }
    return problem;
  }
}
