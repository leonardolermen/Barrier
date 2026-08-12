package com.barrier.riskengine.screening.rule.interfaces;

import com.barrier.riskengine.screening.domain.ScreeningHit;
import com.barrier.riskengine.screening.rule.context.ScreeningContext;

import java.util.List;

/**
 * Regra de screening (Strategy). Cada regra avalia o contexto e contribui com zero ou mais
 * apontamentos. O serviço aplica todas as regras em cadeia e agrega os resultados.
 */
public interface ScreeningRule {

  List<ScreeningHit> evaluate(ScreeningContext context);
}
