package com.barrier.riskengine.history.domain;

import java.time.Instant;
import java.util.UUID;

/** Um evento de histórico interno do subject (auditoria/explicabilidade de risco). */
public record SubjectHistoryEvent(
    UUID id,
    UUID subjectId,
    HistoryEventType eventType,
    String detail,
    Instant occurredAt,
    Instant createdAt) {

  public static SubjectHistoryEvent create(
      UUID subjectId, HistoryEventType eventType, String detail, Instant occurredAt) {
    return new SubjectHistoryEvent(
        UUID.randomUUID(), subjectId, eventType, detail, occurredAt, Instant.now());
  }
}
