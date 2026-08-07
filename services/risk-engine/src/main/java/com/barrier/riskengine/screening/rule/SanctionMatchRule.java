package com.barrier.riskengine.screening.rule;

import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import java.util.List;
import org.springframework.stereotype.Component;

/** Regra: registros de sanção nas listas viram apontamentos. */
@Component
public class SanctionMatchRule implements ScreeningRule {

  @Override
  public List<ScreeningHit> evaluate(ScreeningContext context) {
    return context.entries().stream()
        .filter(e -> e.type() == MatchType.SANCTION)
        .map(
            e ->
                new ScreeningHit(
                    MatchType.SANCTION, e.basis(), e.source(), e.matchedName(), e.detail()))
        .toList();
  }
}
