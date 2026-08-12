package com.barrier.riskengine.risk.rule.interfaces;

import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.RiskContext;

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

  /**
   * Código estável da família de regra (ex.: {@code NEW_COMPANY}), usado pelo registry de
   * regras ({@code RiskRuleRegistryService}) para habilitar/desabilitar e definir vigência sem
   * deploy — independente do {@code ruleCode} granular que a regra pode variar em
   * {@link RiskResult} (ex.: {@code IdentityRiskRule} devolve códigos diferentes por desfecho,
   * mas pertence à família {@code IDENTITY}).
   */
  String code();
}
