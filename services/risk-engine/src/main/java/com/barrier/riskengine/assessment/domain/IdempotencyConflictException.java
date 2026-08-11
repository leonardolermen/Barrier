package com.barrier.riskengine.assessment.domain;

/**
 * A chave de idempotência não pode ser honrada como repetição: ou está sendo usada por uma
 * submissão diferente, ou a submissão original ainda não terminou.
 *
 * <p>Nos dois casos a resposta é 409 e não um erro de servidor — repetir a mesma requisição não
 * ajuda, e criar uma avaliação nova seria exatamente o que a chave existe para impedir.
 */
public class IdempotencyConflictException extends RuntimeException {

  private IdempotencyConflictException(String message) {
    super(message);
  }

  /** Mesma chave, conteúdo diferente: erro do cliente, servido pela resposta antiga seria pior. */
  public static IdempotencyConflictException differentRequest(String key) {
    return new IdempotencyConflictException(
        "Idempotency-Key '" + key + "' já foi usada para uma submissão com conteúdo diferente");
  }

  /** A submissão original ainda está em curso; não há avaliação para devolver. */
  public static IdempotencyConflictException inProgress(String key) {
    return new IdempotencyConflictException(
        "Submissão com Idempotency-Key '" + key + "' está em andamento; tente novamente em instantes");
  }
}
