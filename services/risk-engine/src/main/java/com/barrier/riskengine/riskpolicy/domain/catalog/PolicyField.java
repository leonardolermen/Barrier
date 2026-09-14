package com.barrier.riskengine.riskpolicy.domain.catalog;

import com.barrier.riskengine.risk.rule.context.ContextInput;
import java.util.function.Function;

/**
 * Um campo que a política pode referenciar.
 *
 * @param id identificador estável que o parceiro escreve ({@code company.openingDate})
 * @param type tipo, que decide os operadores válidos
 * @param input insumo do {@code RiskContext} de onde este campo sai — é daqui que o
 *     {@code requires()} da política é derivado, em vez de declarado por humano
 * @param exposure se o valor pode aparecer na evidência
 * @param parentListId {@code null} para campo de topo; para campo de elemento, o id da lista a que
 *     ele pertence. É o que permite a compilação recusar {@code partners[].foreign} fora de um
 *     {@code AnyOf} sobre {@code company.partners}
 * @param extractor extrai o valor da raiz — o {@code RiskContext} para campo de topo, o elemento da
 *     lista para campo de elemento
 */
public record PolicyField(
    String id,
    PolicyFieldType type,
    ContextInput input,
    EvidenceExposure exposure,
    String parentListId,
    Function<Object, Object> extractor) {

  /** Resolve o valor; devolve {@code null} quando o caminho não existe no contexto. */
  public Object resolve(Object root) {
    if (root == null) {
      return null;
    }
    return extractor.apply(root);
  }

  public boolean isElementField() {
    return parentListId != null;
  }
}
