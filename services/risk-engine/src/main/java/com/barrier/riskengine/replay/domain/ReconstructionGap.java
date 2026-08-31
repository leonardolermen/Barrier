package com.barrier.riskengine.replay.domain;

import com.barrier.riskengine.risk.rule.context.ContextInput;

/**
 * Um insumo que não foi reconstruído, com o motivo em texto.
 *
 * @param kind por que faltou
 * @param input campo do {@code RiskContext} afetado; {@code null} quando a lacuna não é de contexto
 *     (o caso de {@link GapKind#EVALUATED_TRAIL_ABSENT}, que é lacuna de trilha)
 * @param detail explicação legível, sem PII
 */
public record ReconstructionGap(GapKind kind, ContextInput input, String detail) {

  public static ReconstructionGap of(GapKind kind, ContextInput input, String detail) {
    return new ReconstructionGap(kind, input, detail);
  }

  public static ReconstructionGap trail(GapKind kind, String detail) {
    return new ReconstructionGap(kind, null, detail);
  }
}
