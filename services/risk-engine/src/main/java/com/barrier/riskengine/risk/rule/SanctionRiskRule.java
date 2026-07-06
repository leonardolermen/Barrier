package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.screening.domain.MatchType;
import java.util.List;
import org.springframework.stereotype.Component;

/** Sanção (OFAC/ONU/CGU) é bloqueio: o cliente não pode operar. */
@Component
public class SanctionRiskRule implements RiskRule {

  @Override
  public RiskResult evaluate(RiskContext context) {
    List<String> evidences =
        context.screening().hits().stream()
            .filter(h -> h.type() == MatchType.SANCTION)
            .map(h -> h.source() + ":" + h.matchedName())
            .toList();

    if (evidences.isEmpty()) {
      return RiskResult.notApplicable("SANCTION");
    }
    return new RiskResult(
        "SANCTION_HIT",
        1000,
        Severity.CRITICAL,
        "Apontamento em lista de sanções",
        evidences,
        RiskRecommendation.REJECT);
  }
}
