package com.barrier.riskengine.screening.domain;

import com.barrier.riskengine.screening.domain.enums.ScreeningStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resultado persistido do screening de uma avaliação.
 *
 * @param id identificador do registro
 * @param assessmentId avaliação à qual pertence (correlação)
 * @param status CLEAR quando não há apontamentos, HIT caso contrário
 * @param hits apontamentos encontrados (vazio quando CLEAR)
 * @param sources fonte → versão da lista consultada neste screening. É o que torna o CLEAR
 *     verificável depois: a base é substituída todo dia, e sem o snapshot não há como responder se
 *     um nome estava na lista <b>naquele dia</b>
 * @param checkedAt instante do screening
 */
public record ScreeningResult(
    UUID id,
    String assessmentId,
    ScreeningStatus status,
    List<ScreeningHit> hits,
    Map<String, String> sources,
    Instant checkedAt) {

  public ScreeningResult {
    hits = List.copyOf(hits);
    sources = sources == null ? Map.of() : Map.copyOf(sources);
  }

  /** Cria um resultado novo; o status é derivado da presença de apontamentos. */
  public static ScreeningResult of(
      String assessmentId, List<ScreeningHit> hits, Map<String, String> sources) {
    ScreeningStatus status = hits.isEmpty() ? ScreeningStatus.CLEAR : ScreeningStatus.HIT;
    return new ScreeningResult(
        UUID.randomUUID(), assessmentId, status, hits, sources, Instant.now());
  }

  /** Sem snapshot de listas — testes e resultados anteriores à V028. */
  public static ScreeningResult of(String assessmentId, List<ScreeningHit> hits) {
    return of(assessmentId, hits, Map.of());
  }

  public boolean hasHits() {
    return status == ScreeningStatus.HIT;
  }
}
