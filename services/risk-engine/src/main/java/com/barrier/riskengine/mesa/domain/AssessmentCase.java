package com.barrier.riskengine.mesa.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * O caso operacional de uma avaliação em revisão: em que fila está, de quem é, quando abriu e
 * quando fechou.
 *
 * <p>Separado do {@code Assessment} de propósito: aquele é a decisão de risco, este é o trabalho da
 * operação sobre ela. A fronteira entre "o que o motor decidiu" e "o que a mesa fez" é justamente a
 * que precisa ficar nítida numa fiscalização.
 */
public record AssessmentCase(
    UUID assessmentId,
    String tenantId,
    CaseQueue queue,
    String assignedTo,
    Instant openedAt,
    Instant closedAt) {

  public static AssessmentCase open(UUID assessmentId, String tenantId, CaseQueue queue) {
    return new AssessmentCase(assessmentId, tenantId, queue, null, Instant.now(), null);
  }

  public boolean isOpen() {
    return closedAt == null;
  }

  public AssessmentCase assignTo(String analyst) {
    return new AssessmentCase(assessmentId, tenantId, queue, analyst, openedAt, closedAt);
  }

  public AssessmentCase moveTo(CaseQueue destino) {
    return new AssessmentCase(assessmentId, tenantId, destino, assignedTo, openedAt, closedAt);
  }

  public AssessmentCase close() {
    return new AssessmentCase(
        assessmentId, tenantId, queue, assignedTo, openedAt, Instant.now());
  }
}
