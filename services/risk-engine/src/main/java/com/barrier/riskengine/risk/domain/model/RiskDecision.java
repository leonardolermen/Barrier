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
 * @param policyVersion versão da política custom do tenant que contribuiu regras a esta decisão;
 *     {@code null} quando o tenant não tem política ativa — segundo eixo de versão, ao lado de
 *     {@code engineVersion}
 */
public record RiskDecision(
    RiskLevel level,
    RiskRecommendation recommendation,
    int totalScore,
    List<RiskResult> results,
    List<EvaluatedRule> evaluated,
    String engineVersion,
    Integer policyVersion) {

  public RiskDecision {
    results = List.copyOf(results);
    evaluated = evaluated == null ? List.of() : List.copyOf(evaluated);
  }

  /**
   * Decisão sem a trilha completa de regras e sem política custom — usado por testes e por
   * decisões históricas (anteriores à política custom, que por isso não a carregam).
   */
  public RiskDecision(
      RiskLevel level,
      RiskRecommendation recommendation,
      int totalScore,
      List<RiskResult> results,
      String engineVersion) {
    this(level, recommendation, totalScore, results, List.of(), engineVersion, null);
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
