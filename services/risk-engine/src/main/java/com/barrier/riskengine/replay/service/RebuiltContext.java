package com.barrier.riskengine.replay.service;

import com.barrier.riskengine.replay.domain.ReconstructionGap;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import java.util.List;
import java.util.Set;

/**
 * O contexto remontado para a reexecução, junto com a lista do que <b>não</b> foi possível remontar.
 *
 * @param unreliable insumos que não representam o estado da época. Uma regra que declare qualquer um
 *     deles em {@code RiskRule.requires()} não pode ser reexecutada honestamente — ver
 *     {@code RuleComparison.NOT_REPLAYABLE}
 */
public record RebuiltContext(
    RiskContext context, Set<ContextInput> unreliable, List<ReconstructionGap> gaps) {

  public RebuiltContext {
    unreliable = Set.copyOf(unreliable);
    gaps = List.copyOf(gaps);
  }
}
