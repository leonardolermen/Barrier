package com.barrier.riskengine.riskstate.domain;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;

/**
 * Mudança de nível de risco corrente de um cliente — o fato que o parceiro quer receber.
 *
 * <p>Só existe quando havia um nível anterior <b>e</b> ele é diferente do novo. Primeira avaliação
 * de um cliente não produz transição: {@code null → LOW} não é "o risco mudou", é "o cliente
 * passou a existir", e o parceiro já soube disso pelo {@code barrier.assessment.completed}.
 */
public record RiskLevelTransition(RiskLevel from, RiskLevel to) {

  public RiskLevelTransition {
    if (from == null || to == null) {
      throw new IllegalArgumentException("transição exige nível anterior e novo");
    }
    if (from == to) {
      throw new IllegalArgumentException("transição exige níveis diferentes: " + from);
    }
  }

  /** Verdadeiro quando o cliente piorou (a escala do Barrier é maior = pior). */
  public boolean worsened() {
    return to.ordinal() > from.ordinal();
  }
}
