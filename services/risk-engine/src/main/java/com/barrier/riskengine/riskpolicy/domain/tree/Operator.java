package com.barrier.riskengine.riskpolicy.domain.tree;

import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyFieldType;
import java.util.Set;

/**
 * Operador de uma {@link Condition.Comparison}. Cada operador declara os tipos de campo que
 * aceita — a compilação (Task 4) recusa combinação inválida.
 */
public enum Operator {
  EQ(PolicyFieldType.values()),
  NEQ(PolicyFieldType.values()),
  IS_NULL(PolicyFieldType.values()),
  IS_NOT_NULL(PolicyFieldType.values()),
  IN(PolicyFieldType.STRING, PolicyFieldType.NUMBER, PolicyFieldType.ENUM),
  NOT_IN(PolicyFieldType.STRING, PolicyFieldType.NUMBER, PolicyFieldType.ENUM),
  STARTS_WITH(PolicyFieldType.STRING),
  LT(PolicyFieldType.NUMBER),
  LTE(PolicyFieldType.NUMBER),
  GT(PolicyFieldType.NUMBER),
  GTE(PolicyFieldType.NUMBER),
  BEFORE(PolicyFieldType.DATE),
  AFTER(PolicyFieldType.DATE),
  OLDER_THAN(PolicyFieldType.DATE),
  WITHIN_LAST(PolicyFieldType.DATE);

  private final Set<PolicyFieldType> supported;

  Operator(PolicyFieldType... tipos) {
    this.supported = Set.of(tipos);
  }

  public boolean supports(PolicyFieldType type) {
    return supported.contains(type);
  }
}
