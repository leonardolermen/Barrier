package com.barrier.riskengine.behavior.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Fato comportamental submetido pelo parceiro.
 *
 * @param payload conteúdo livre, acordado por contrato. Fica no acervo e <b>não</b> viaja no evento
 *     do barramento — ver {@code BehaviorRecordedPayload}
 * @param sourceEventId id do evento no sistema do parceiro; é a chave de idempotência, e sem ela
 *     um reprocessamento da fila dele contaria a mesma transação duas vezes
 */
public record BehaviorEventRequest(
    @NotBlank String documentType,
    @NotBlank String document,
    @NotBlank String name,
    @NotBlank @Size(max = 60) String eventType,
    @NotNull Instant occurredAt,
    String payload,
    @NotBlank @Size(max = 120) String sourceEventId) {}
