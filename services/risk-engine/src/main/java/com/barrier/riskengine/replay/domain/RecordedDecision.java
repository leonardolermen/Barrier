package com.barrier.riskengine.replay.domain;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * O que foi decidido, e com base em quê — a metade do dossiê que não depende de reexecutar nada.
 *
 * @param watchlistVersions fonte → versão da lista consultada no screening. É o que torna um
 *     {@code CLEAR} verificável meses depois: a base é substituída todo dia
 * @param identityCheckId a verificação de identidade exata que sustentou a decisão (V028)
 * @param screeningResultId o screening exato que sustentou a decisão (V028)
 */
public record RecordedDecision(
    RiskLevel level,
    int score,
    RiskRecommendation recommendation,
    String engineVersion,
    Instant decidedAt,
    UUID identityCheckId,
    UUID screeningResultId,
    Map<String, String> watchlistVersions) {

  public RecordedDecision {
    watchlistVersions = watchlistVersions == null ? Map.of() : Map.copyOf(watchlistVersions);
  }
}
