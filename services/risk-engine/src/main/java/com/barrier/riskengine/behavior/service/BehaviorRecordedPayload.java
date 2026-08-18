package com.barrier.riskengine.behavior.service;

import com.barrier.riskengine.behavior.domain.BehaviorEvent;
import java.time.Instant;

/**
 * Conteúdo de {@code barrier.behavior.recorded}.
 *
 * <p>O payload do parceiro <b>não</b> é repassado: ele é livre por contrato e pode conter qualquer
 * coisa que o parceiro resolveu mandar, inclusive dado pessoal que não temos base para espalhar
 * pelo barramento. Quem precisar do conteúdo lê o acervo; o evento anuncia que o fato existe.
 */
public record BehaviorRecordedPayload(
    String tenantId,
    String subjectId,
    String eventId,
    String eventType,
    Instant occurredAt,
    Instant receivedAt) {

  public static BehaviorRecordedPayload from(BehaviorEvent event) {
    return new BehaviorRecordedPayload(
        event.tenantId(),
        event.subjectId().toString(),
        event.id().toString(),
        event.eventType(),
        event.occurredAt(),
        event.receivedAt());
  }
}
