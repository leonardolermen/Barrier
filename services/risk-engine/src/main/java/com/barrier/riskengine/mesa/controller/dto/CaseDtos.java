package com.barrier.riskengine.mesa.controller.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

/** DTOs da mesa. Agrupados porque são pequenos e sempre lidos juntos. */
public final class CaseDtos {

  private CaseDtos() {}

  /** Item da fila. {@code slaSeconds} já vem com a espera do parceiro descontada. */
  public record CaseSummary(
      String assessmentId,
      String queue,
      String assignedTo,
      Instant openedAt,
      Instant closedAt,
      long slaSeconds) {}

  public record CaseTimeline(CaseSummary caso, List<ActionEntry> actions) {}

  public record ActionEntry(String type, String actor, String detail, Instant occurredAt) {}

  public record AssignRequest(@NotBlank String analyst) {}

  public record MoveRequest(@NotBlank String queue, @NotBlank String actor) {}

  /** {@code detail} descreve o que foi pedido — nunca dado do cliente. */
  public record DocumentRequest(@NotBlank String actor, String detail) {}

  public record NoteRequest(@NotBlank String actor, @NotBlank String text) {}
}
