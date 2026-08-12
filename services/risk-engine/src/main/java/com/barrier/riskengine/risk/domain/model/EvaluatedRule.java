package com.barrier.riskengine.risk.domain.model;

import com.barrier.riskengine.risk.rule.interfaces.RiskRule;

/**
 * O que aconteceu com <b>cada</b> regra do motor numa decisão — inclusive as que não dispararam.
 *
 * <p>O motor gravava apenas as regras que contribuíram. O efeito é que "a regra de sanção não
 * aparece na trilha" tinha três leituras indistinguíveis: ela rodou e o cliente estava limpo, ela
 * estava desligada no registry, ou a lista estava vazia. As três produzem a mesma ausência, e só
 * uma delas é aceitável. Provar que um controle <i>rodou e passou</i> é o núcleo da auditabilidade
 * — a ausência de evidência não pode ser lida como evidência de ausência.
 *
 * @param ruleCode família da regra ({@link RiskRule#code()})
 * @param outcome o que aconteceu com ela
 * @param result resultado produzido; {@code null} quando a regra não chegou a ser executada
 */
public record EvaluatedRule(String ruleCode, RuleOutcome outcome, RiskResult result) {

  public static EvaluatedRule triggered(String ruleCode, RiskResult result) {
    return new EvaluatedRule(ruleCode, RuleOutcome.TRIGGERED, result);
  }

  /** Rodou e passou — a linha que prova que o controle foi exercido. */
  public static EvaluatedRule passed(String ruleCode, RiskResult result) {
    return new EvaluatedRule(ruleCode, RuleOutcome.NOT_TRIGGERED, result);
  }

  /** Não rodou: desabilitada ou fora de vigência no registry. */
  public static EvaluatedRule suppressed(String ruleCode) {
    return new EvaluatedRule(ruleCode, RuleOutcome.SUPPRESSED, null);
  }
}
