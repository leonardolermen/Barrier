package com.barrier.riskengine.risk.domain.model;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import java.util.List;

/**
 * Decisão consolidada do motor de risco.
 *
 * @param level nível de risco (banda do score)
 * @param recommendation recomendação (aprovar/revisar/reprovar)
 * @param totalScore pontuação total (0–1000)
 * @param results resultados das regras que dispararam (explicabilidade)
 * @param evaluated <b>todas</b> as regras do motor com o que aconteceu com cada uma, inclusive as
 *     que passaram e as suprimidas pelo registry — é o que permite provar que um controle rodou
 *     (ver {@link EvaluatedRule})
 * @param engineVersion versão do conjunto de regras que produziu a decisão (auditoria)
 */
public record RiskDecision(
    RiskLevel level,
    RiskRecommendation recommendation,
    int totalScore,
    List<RiskResult> results,
    List<EvaluatedRule> evaluated,
    String engineVersion) {

  public RiskDecision {
    results = List.copyOf(results);
    evaluated = evaluated == null ? List.of() : List.copyOf(evaluated);
  }

  /** Decisão sem a trilha completa de regras — usado por testes e por decisões históricas. */
  public RiskDecision(
      RiskLevel level,
      RiskRecommendation recommendation,
      int totalScore,
      List<RiskResult> results,
      String engineVersion) {
    this(level, recommendation, totalScore, results, List.of(), engineVersion);
  }

  /** Explicações legíveis (código, pontos e motivo de cada regra que disparou). */
  public List<String> explanations() {
    return results.stream()
        .map(r -> r.ruleCode() + " (+" + r.score() + "): " + r.reason())
        .toList();
  }

  public String summary() {
    return recommendation + " · score " + totalScore + "/1000 · risco " + level;
  }
}
