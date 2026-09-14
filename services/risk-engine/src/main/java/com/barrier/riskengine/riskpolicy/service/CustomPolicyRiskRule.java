package com.barrier.riskengine.riskpolicy.service;

import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import java.util.Set;

/**
 * Adapta uma {@link PolicyRule} do parceiro para o vocabulário do motor ({@link RiskRule}).
 *
 * <p><b>Não é bean.</b> Uma instância existe por regra da política ativa do tenant, montada por
 * avaliação a partir de {@code CustomRuleSource.forContext} — diferente das regras de código,
 * que são {@code @Component} singleton porque não variam por tenant.
 *
 * <p>{@link #requires()} é calculado uma vez no construtor a partir de
 * {@link ContextInputDerivation}, caminhando a árvore da regra: não é declarado à mão, então
 * não pode ser declarado errado — diferente de {@code RiskRule.requires()} em código, onde o
 * {@code RiskRuleContextDeclarationTest} precisa provar por bytecode que a declaração bate com
 * o uso real.
 */
public class CustomPolicyRiskRule implements RiskRule {

  private final PolicyRule rule;
  private final ConditionEvaluator evaluator;
  private final Set<ContextInput> requires;

  public CustomPolicyRiskRule(PolicyRule rule, ConditionEvaluator evaluator) {
    this.rule = rule;
    this.evaluator = evaluator;
    this.requires = ContextInputDerivation.of(rule.when());
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    ConditionEvaluator.ClauseEvaluation resultado = evaluator.evaluate(rule.when(), context);
    if (!resultado.matched()) {
      return RiskResult.notApplicable(rule.code());
    }
    return new RiskResult(
        rule.code(),
        rule.score(),
        rule.severity(),
        "política do parceiro: " + rule.name(),
        resultado.evidences(),
        rule.recommendation());
  }

  @Override
  public String code() {
    return rule.code();
  }

  @Override
  public Set<ContextInput> requires() {
    return requires;
  }
}
