package com.barrier.riskengine.behavior.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Um fato comportamental sobre um cliente, informado pelo parceiro.
 *
 * <p><b>Imutável por construção.</b> Não existe método que altere um evento gravado: ele descreve
 * algo que aconteceu, e corrigir o passado destruiria a base sobre a qual uma decisão foi tomada.
 * Correção se faz com evento novo.
 *
 * @param occurredAt quando aconteceu no mundo, segundo o parceiro
 * @param receivedAt quando chegou aqui. A diferença entre os dois é a latência da integração — sem
 *     ela, parceiro reenviando histórico antigo é indistinguível de parceiro atrasado
 * @param sourceEventId id do evento no sistema do parceiro; é a chave de idempotência
 */
public record BehaviorEvent(
    UUID id,
    String tenantId,
    UUID subjectId,
    String eventType,
    Instant occurredAt,
    Instant receivedAt,
    String payload,
    String sourceEventId) {

  public static BehaviorEvent of(
      String tenantId,
      UUID subjectId,
      String eventType,
      Instant occurredAt,
      String payload,
      String sourceEventId) {
    return new BehaviorEvent(
        UUID.randomUUID(),
        tenantId,
        subjectId,
        eventType,
        occurredAt,
        Instant.now(),
        payload,
        sourceEventId);
  }
}
