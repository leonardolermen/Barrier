package com.barrier.riskengine.riskpolicy.domain.catalog;

/**
 * Se o <b>valor</b> do campo pode aparecer na evidência da regra.
 *
 * <p>Evidência de regra viaja para dentro de {@code evaluated_json}, volta no {@code GET} da
 * avaliação e entra no dossiê de replay — lugares cujo controle de acesso é mais fraco que o da
 * coluna original. Campo {@link #OUTCOME_ONLY} registra apenas o resultado da comparação, nunca o
 * valor comparado. É a mesma regra que permite que um alerta do módulo {@code monitoring} circule
 * em canal externo.
 */
public enum EvidenceExposure {
  BY_VALUE,
  OUTCOME_ONLY
}
