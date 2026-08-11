package com.barrier.riskengine.web;

import com.barrier.riskengine.assessment.domain.AssessmentNotFoundException;
import com.barrier.riskengine.assessment.domain.IdempotencyConflictException;
import com.barrier.riskengine.assessment.domain.InvalidDocumentException;
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

  @ExceptionHandler(IllegalStateException.class)
  ProblemDetail handleConflict(IllegalStateException e) {
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
