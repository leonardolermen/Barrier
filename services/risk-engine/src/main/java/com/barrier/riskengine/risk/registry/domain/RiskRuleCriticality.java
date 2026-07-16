package com.barrier.riskengine.risk.registry.domain;

/** Criticidade informativa de uma família de regra, para transparência de auditoria. */
public enum RiskRuleCriticality {
  /** Só contribui pro score; nunca decide sozinha. */
  INFO,
  /** Fator de atenção — soma score relevante, mas a banda decide. */
  ALERT,
  /** Força revisão humana (EDD), independente da banda. */
  REVIEW,
  /** Força bloqueio — cliente não pode operar. */
  BLOCK
}
