package com.barrier.riskengine.screening.rule;

import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import java.util.List;
import org.springframework.stereotype.Component;

/** Regra: registros PEP nas listas viram apontamentos. */
@Component
public class PepMatchRule implements ScreeningRule {

  @Override
  public List<ScreeningHit> evaluate(ScreeningContext context) {
    return context.entries().stream()
        .filter(e -> e.type() == MatchType.PEP)
        .map(e -> new ScreeningHit(
                    MatchType.PEP, e.basis(), e.party(), e.source(), e.matchedName(), e.detail()))
        .toList();
  }
}
