package com.barrier.riskengine.mesa.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma ação manual sobre o caso.
 *
 * @param actor quem fez — texto autodeclarado, mesmo padrão de {@code assessments.reviewed_by}. Não
 *     é credencial verificada; quando houver auth de mesa, o rótulo da chave entra ao lado, como
 *     {@code reviewed_by_key} fez para a decisão.
 * @param detail contexto legível (fila de origem e destino, qual documento foi pedido). Nunca
 *     documento do cliente nem valor de campo — a mesa lê isso no cadastro, não na trilha de ação.
 */
public record CaseAction(
    UUID id,
    UUID assessmentId,
    String tenantId,
    CaseActionType type,
    String actor,
    String detail,
    Instant occurredAt) {

  public static CaseAction of(
      UUID assessmentId, String tenantId, CaseActionType type, String actor, String detail) {
    return new CaseAction(
        UUID.randomUUID(), assessmentId, tenantId, type, actor, detail, Instant.now());
  }
}
