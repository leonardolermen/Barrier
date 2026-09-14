package com.barrier.riskengine.riskpolicy.domain.tree;

import com.barrier.riskengine.riskpolicy.domain.catalog.PolicyField;
import java.util.List;

/**
 * Árvore de predicados de uma regra de política.
 *
 * <p><b>Total por construção</b>: não há laço, recursão sobre dado nem chamada de função. Uma
 * política não tem como não terminar, o que é o que permite avaliá-la dentro do caminho de
 * decisão sem orçamento de tempo. Profundidade e número de nós têm teto, verificados na
 * compilação.
 */
public sealed interface Condition {

  record And(List<Condition> operands) implements Condition {
    public And {
      operands = List.copyOf(operands);
    }
  }

  record Or(List<Condition> operands) implements Condition {
    public Or {
      operands = List.copyOf(operands);
    }
  }

  record Not(Condition operand) implements Condition {}

  record Comparison(PolicyField field, Operator op, Literal value) implements Condition {}

  /**
   * Verdadeiro quando <b>algum</b> elemento da lista satisfaz {@code each}.
   *
   * <p>Não existe nó {@code NoneOf}: é {@code Not(AnyOf(...))}. Lista vazia ou ausente avalia
   * falso, que é o que faz uma PJ sem QSA não casar em vez de estourar.
   */
  record AnyOf(PolicyField listField, Condition each) implements Condition {}
}
