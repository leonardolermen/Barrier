package com.barrier.riskengine.screening.rule;

import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import java.util.List;

import com.barrier.riskengine.screening.rule.context.ScreeningContext;
import com.barrier.riskengine.screening.rule.interfaces.ScreeningRule;
import org.springframework.stereotype.Component;

/** Regra: registros de mídia negativa nas listas viram apontamentos. */
@Component
public class AdverseMediaMatchRule implements ScreeningRule {

  @Override
  public List<ScreeningHit> evaluate(ScreeningContext context) {
    return context.entries().stream()
        .filter(e -> e.type() == MatchType.ADVERSE_MEDIA)
        .map(
            e ->
                new ScreeningHit(
                    MatchType.ADVERSE_MEDIA, e.basis(), e.party(), e.source(), e.matchedName(), e.detail()))
        .toList();
  }
}
