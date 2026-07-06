package com.barrier.riskengine.screening.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resultado persistido do screening de uma avaliação.
 *
 * @param id identificador do registro
 * @param assessmentId avaliação à qual pertence (correlação)
 * @param status CLEAR quando não há apontamentos, HIT caso contrário
 * @param hits apontamentos encontrados (vazio quando CLEAR)
 * @param checkedAt instante do screening
 */
public record ScreeningResult(
    UUID id,
    String assessmentId,
    ScreeningStatus status,
    List<ScreeningHit> hits,
    Instant checkedAt) {

  public ScreeningResult {
    hits = List.copyOf(hits);
  }

  /** Cria um resultado novo; o status é derivado da presença de apontamentos. */
  public static ScreeningResult of(String assessmentId, List<ScreeningHit> hits) {
    ScreeningStatus status = hits.isEmpty() ? ScreeningStatus.CLEAR : ScreeningStatus.HIT;
    return new ScreeningResult(UUID.randomUUID(), assessmentId, status, hits, Instant.now());
  }

  public boolean hasHits() {
    return status == ScreeningStatus.HIT;
  }
}
