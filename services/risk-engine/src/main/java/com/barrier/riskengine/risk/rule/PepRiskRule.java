package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.screening.domain.MatchType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * PEP (Pessoa Exposta Politicamente) exige due diligence reforçada (EDD): força REVIEW,
 * independentemente do score, conforme a Circular BCB 3.978.
 */
@Component
public class PepRiskRule implements RiskRule {

  @Override
  public RiskResult evaluate(RiskContext context) {
    List<String> evidences =
        context.screening().hits().stream()
            .filter(h -> h.type() == MatchType.PEP)
            .map(h -> h.source() + ":" + h.matchedName())
            .toList();

    if (evidences.isEmpty()) {
      return RiskResult.notApplicable("PEP");
    }
    return new RiskResult(
        "PEP",
        300,
        Severity.HIGH,
        "Pessoa Exposta Politicamente — EDD requerido",
        evidences,
        RiskRecommendation.REVIEW);
  }

  @Override
  public String code() {
    return "PEP";
  }
}
