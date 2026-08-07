package com.barrier.riskengine.screening.domain;

/**
 * Um apontamento encontrado no screening.
 *
 * @param type categoria (PEP, sanção, mídia adversa)
 * @param basis se casou por documento (inequívoco) ou por nome (indício) — ver {@link MatchBasis}
 * @param source origem da lista (ex.: OFAC, ONU, CGU)
 * @param matchedName nome que casou na lista
 * @param detail descrição legível do apontamento
 */
public record ScreeningHit(
    MatchType type, MatchBasis basis, String source, String matchedName, String detail) {

  /**
   * Apontamento histórico gravado antes de {@link MatchBasis} existir vem sem {@code basis} no
   * {@code hits_json}. Assume-se {@link MatchBasis#NAME} — a interpretação mais fraca, para não
   * atribuir a um registro antigo uma certeza que ele não registrou.
   */
  public ScreeningHit {
    basis = basis == null ? MatchBasis.NAME : basis;
  }
}
