package com.barrier.riskengine.risk.domain.enums;

/**
 * Recomendação de decisão, em ordem crescente de severidade. O motor toma a mais severa entre
 * a banda de score e os overrides das regras.
 */
public enum RiskRecommendation {
  APPROVE,
  REVIEW,
  REJECT;

  /** Retorna a recomendação mais severa entre as duas. */
  public RiskRecommendation strongest(RiskRecommendation other) {
    return this.ordinal() >= other.ordinal() ? this : other;
  }
}
