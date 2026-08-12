package com.barrier.riskengine.assessment.domain;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentId;

/**
 * Estado de uma chave de idempotência do intake.
 *
 * @param requestHash impressão digital da requisição que reservou a chave
 * @param assessmentId avaliação já criada sob esta chave; {@code null} enquanto a submissão que a
 *     reservou ainda não terminou (reserva em andamento)
 * @param fresh {@code true} quando esta requisição tomou a posse da chave (nova ou fora da janela)
 *     e portanto deve criar a avaliação; {@code false} quando encontrou uma reserva de outra
 *     requisição
 */
public record IdempotencyReservation(String requestHash, AssessmentId assessmentId, boolean fresh) {

  public static IdempotencyReservation taken(String requestHash) {
    return new IdempotencyReservation(requestHash, null, true);
  }

  /** Reserva ainda sem avaliação: outra requisição com a mesma chave está em andamento. */
  public boolean inProgress() {
    return assessmentId == null;
  }
}
