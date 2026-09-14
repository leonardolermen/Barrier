package com.barrier.riskengine.riskpolicy.service;

import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import java.util.EnumSet;
import java.util.Set;

/**
 * Deriva o {@code requires()} de uma política caminhando a árvore, em vez de o parceiro (ou o
 * código que adapta {@code PolicyRule} a {@code RiskRule}) declarar à mão.
 *
 * <p>A árvore já nomeia todo campo que lê; acumular {@link
 * com.barrier.riskengine.riskpolicy.domain.catalog.PolicyField#input()} de cada {@code
 * Comparison} e de toda {@code listField} de {@code AnyOf} é o insumo inteiro, sem uma segunda
 * fonte de verdade que possa divergir da primeira — diferente do caminho de código, onde um teste
 * de bytecode ({@code RiskRuleContextDeclarationTest}) precisa provar que a declaração manual bate
 * com o que a regra de fato lê.
 */
public final class ContextInputDerivation {

  private ContextInputDerivation() {}

  public static Set<ContextInput> of(Condition condition) {
    Set<ContextInput> inputs = EnumSet.noneOf(ContextInput.class);
    acumular(condition, inputs);
    return inputs;
  }

  private static void acumular(Condition condition, Set<ContextInput> inputs) {
    switch (condition) {
      case Condition.And and -> and.operands().forEach(op -> acumular(op, inputs));
      case Condition.Or or -> or.operands().forEach(op -> acumular(op, inputs));
      case Condition.Not not -> acumular(not.operand(), inputs);
      case Condition.Comparison comparison -> inputs.add(comparison.field().input());
      case Condition.AnyOf anyOf -> {
        inputs.add(anyOf.listField().input());
        acumular(anyOf.each(), inputs);
      }
    }
  }
}
