package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.screening.domain.MatchType;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mídia negativa (lavagem, corrupção, fraude, tráfico, terrorismo, pirâmide financeira) não
 * bloqueia sozinha — como PEP, força revisão humana (EDD): um apontamento de mídia pode ser
 * homônimo ou desatualizado, exige julgamento de analista antes de reprovar.
 */
@Component
public class NegativeMediaRiskRule implements RiskRule {

  private final int score;

  public NegativeMediaRiskRule(@Value("${barrier.risk.negative-media-score:250}") int score) {
    this.score = score;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    List<String> evidences =
        context.screening().hits().stream()
            .filter(h -> h.type() == MatchType.ADVERSE_MEDIA)
            .map(h -> h.source() + ":" + h.matchedName())
            .toList();

    if (evidences.isEmpty()) {
      return RiskResult.notApplicable("NEGATIVE_MEDIA");
    }
    return new RiskResult(
        "NEGATIVE_MEDIA",
        score,
        Severity.HIGH,
        "Apontamento em mídia negativa — revisão requerida",
        evidences,
        RiskRecommendation.REVIEW);
  }

  @Override
  public String code() {
    return "NEGATIVE_MEDIA";
  }
}
