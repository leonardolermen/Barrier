package com.barrier.riskengine.replay.domain;

/** O que aconteceu com uma regra entre a decisão gravada e a reexecução. */
public enum RuleComparison {

  /** Modo {@link ReplayMode#AS_DECIDED}: nada foi reexecutado, então não há o que comparar. */
  NOT_COMPARED,

  /** Mesmo desfecho e mesma pontuação. */
  SAME,

  /** Mesmo desfecho, pontuação diferente — tipicamente mudança de peso ou de parâmetro. */
  SCORE_CHANGED,

  /** Disparou e passou a não disparar, ou o contrário. É a diferença que muda a decisão. */
  OUTCOME_CHANGED,

  /** Regra que não existia quando a decisão foi tomada. */
  ADDED,

  /** Regra que existia na decisão gravada e não existe mais no motor. */
  REMOVED,

  /**
   * A regra não pôde ser reexecutada: um dos insumos que ela declara em {@code RiskRule.requires()}
   * não foi reconstruído.
   *
   * <p>Existe para não reportá-la como "passou". Rodar uma regra sobre insumo ausente devolve "não
   * disparou", que é indistinguível de "rodou e o cliente estava limpo" — a mesma ambiguidade que a
   * V028 gastou uma migration para eliminar da trilha.
   */
  NOT_REPLAYABLE
}
