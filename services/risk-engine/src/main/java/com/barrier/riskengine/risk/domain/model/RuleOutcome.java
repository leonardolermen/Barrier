package com.barrier.riskengine.risk.domain.model;

/** Desfecho da avaliação de uma regra numa decisão. Ver {@link EvaluatedRule}. */
public enum RuleOutcome {
  /** Rodou e contribuiu com score e/ou recomendação. */
  TRIGGERED,
  /** Rodou e não se aplicou ao caso — o controle foi exercido e o cliente passou. */
  NOT_TRIGGERED,
  /** Não foi executada: desabilitada ou fora de vigência no registry de regras. */
  SUPPRESSED
}
