package com.barrier.riskengine.risk.domain.model;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Registro persistido de uma pontuação de risco (com a versão do motor, para auditoria). */
public record RiskScore(
    UUID id,
    String assessmentId,
    RiskLevel level,
    int totalScore,
    RiskRecommendation recommendation,
    List<RiskResult> results,
    String engineVersion,
    Instant scoredAt) {

  public RiskScore {
    results = List.copyOf(results);
  }

  public static RiskScore from(String assessmentId, RiskDecision decision) {
    return new RiskScore(
        UUID.randomUUID(),
        assessmentId,
        decision.level(),
        decision.totalScore(),
        decision.recommendation(),
        decision.results(),
        decision.engineVersion(),
        Instant.now());
  }
}
