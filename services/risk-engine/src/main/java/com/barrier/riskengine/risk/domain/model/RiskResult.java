package com.barrier.riskengine.risk.domain.model;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import java.util.List;

/**
 * Resultado padronizado de UMA regra de risco — a unidade de explicabilidade e auditoria.
 *
 * @param ruleCode código estável da regra (ex.: {@code SANCTION_HIT})
 * @param score pontos adicionados ao score (0–1000)
 * @param severity severidade do achado
 * @param reason motivo legível
 * @param evidences evidências utilizadas (ex.: fonte da lista, bureau consultado)
 * @param recommendation recomendação forçada por esta regra; {@code null} quando apenas pontua
 */
public record RiskResult(
    String ruleCode,
    int score,
    Severity severity,
    String reason,
    List<String> evidences,
    RiskRecommendation recommendation) {

  public RiskResult {
    evidences = List.copyOf(evidences);
  }

  /** Regra que não se aplicou ao caso (não entra na decisão). */
  public static RiskResult notApplicable(String ruleCode) {
    return new RiskResult(ruleCode, 0, Severity.LOW, "não aplicável", List.of(), null);
  }

  /** Indica se a regra efetivamente contribuiu (pontuou ou recomendou algo). */
  public boolean triggered() {
    return score > 0 || recommendation != null;
  }
}
