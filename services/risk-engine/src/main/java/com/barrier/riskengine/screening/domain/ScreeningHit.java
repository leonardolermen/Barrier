package com.barrier.riskengine.screening.domain;

/**
 * Um apontamento encontrado no screening.
 *
 * @param type categoria (PEP, sanção, mídia adversa)
 * @param basis se casou por documento (inequívoco) ou por nome (indício) — ver {@link MatchBasis}
 * @param party a quem o apontamento se refere: o titular, um sócio ou o representante legal
 * @param source origem da lista (ex.: OFAC, ONU, CGU)
 * @param matchedName nome que casou na lista
 * @param detail descrição legível do apontamento
 */
public record ScreeningHit(
    MatchType type,
    MatchBasis basis,
    ScreenedParty party,
    String source,
    String matchedName,
    String detail) {

  /**
   * Compatibilidade com o {@code hits_json} já gravado.
   *
   * <p>Apontamento anterior a {@link MatchBasis} vem sem {@code basis}: assume-se
   * {@link MatchBasis#NAME}, a interpretação mais fraca, para não atribuir a um registro antigo uma
   * certeza que ele não registrou. Apontamento anterior ao screening de partes relacionadas vem sem
   * {@code party}: naquele momento só o titular era consultado, então é o que ele significa — e
   * dizer isso explicitamente é melhor que devolver {@code null} para um campo que a trilha de
   * auditoria lê.
   */
  public ScreeningHit {
    basis = basis == null ? MatchBasis.NAME : basis;
    party = party == null ? ScreenedParty.titular(matchedName, null) : party;
  }

  public boolean isTitular() {
    return party.isTitular();
  }
}
