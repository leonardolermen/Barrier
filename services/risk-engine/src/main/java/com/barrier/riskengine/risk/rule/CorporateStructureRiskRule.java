package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.risk.domain.model.RiskResult;
import org.springframework.stereotype.Component;

/**
 * Risco do quadro societário de PJ. Esqueleto ativo (não pontua ainda) porque depende de um
 * provedor de KYB que ainda não temos.
 *
 * <p>Quando o quadro societário estiver disponível no {@link RiskContext}, esta regra deve
 * navegar a árvore de participação <b>até o 3º grau</b> e pontuar:
 *
 * <ul>
 *   <li>sócios estrangeiros;
 *   <li>estruturas em offshore;
 *   <li>trusts;
 *   <li>holdings encadeadas / beneficiário final não identificado.
 * </ul>
 *
 * É um fator de peso alto na abordagem baseada em risco para PJ.
 */
@Component
public class CorporateStructureRiskRule implements RiskRule {

  @Override
  public RiskResult evaluate(RiskContext context) {
    // TODO(fase KYB): integrar provedor de quadro societário e navegar a árvore até 3º grau.
    return RiskResult.notApplicable("CORPORATE_STRUCTURE");
  }
}
