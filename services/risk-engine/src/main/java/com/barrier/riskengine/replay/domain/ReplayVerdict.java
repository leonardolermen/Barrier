package com.barrier.riskengine.replay.domain;

/** Conclusão do replay. A precedência está em {@code DecisionReplayService}. */
public enum ReplayVerdict {

  /** {@link ReplayMode#AS_DECIDED} sem lacuna: o dossiê está completo e a aritmética confere. */
  REPRODUCED,

  /**
   * A soma, a banda ou a recomendação gravadas <b>não</b> batem com o recálculo sobre os resultados
   * persistidos. Precede qualquer outro veredito: é o único que indica trilha adulterada ou
   * corrompida, e não depende de nenhuma reconstrução para ser afirmado.
   */
  TRAIL_INCONSISTENT,

  /** {@link ReplayMode#CURRENT_ENGINE} sem lacuna: o motor de hoje chega ao mesmo desfecho. */
  SAME_DECISION,

  /**
   * {@link ReplayMode#CURRENT_ENGINE} sem lacuna: o motor de hoje chega a desfecho diferente.
   *
   * <p><b>Não é defeito.</b> É a resposta útil — regra nova, peso mudado, regra desligada no
   * registry. O que seria defeito é apresentar isso quando a causa é falta de insumo, e é para
   * impedir exatamente isso que existe {@link #DEGRADED}.
   */
  DIFFERENT_DECISION,

  /**
   * Ao menos um insumo não foi reconstruído, então <b>não dá para afirmar</b> nem que o desfecho se
   * repete nem que muda. As lacunas vêm discriminadas, e as regras afetadas aparecem como
   * {@link RuleComparison#NOT_REPLAYABLE}.
   */
  DEGRADED
}
