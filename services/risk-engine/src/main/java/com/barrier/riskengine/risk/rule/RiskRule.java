package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.risk.domain.model.RiskResult;

/**
 * Regra de risco (Strategy). Cada regra avalia o contexto e devolve um {@link RiskResult}
 * padronizado (score, severidade, motivo, evidências e recomendação). Regras que não se
 * aplicam devolvem {@link RiskResult#notApplicable(String)}.
 *
 * <p>O motor apenas executa todas as regras e agrega — adicionar uma nova fonte (novo bureau,
 * lista de sanções, sinal de fraude) é adicionar uma regra, sem reescrever o motor.
 */
public interface RiskRule {

  RiskResult evaluate(RiskContext context);
}
