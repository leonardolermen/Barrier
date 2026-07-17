package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.credit.client.CreditScoreLookup;
import com.barrier.riskengine.credit.client.CreditScoreProvider;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Score de crédito externo (Serasa/Boa Vista/SCR) abaixo do limiar é fator de atenção — não
 * bloqueia sozinho (score baixo não é sinônimo de fraude, é sinal de risco financeiro).
 */
@Component
public class CreditScoreRiskRule implements RiskRule {

  private final CreditScoreProvider provider;
  private final int threshold;
  private final int score;

  public CreditScoreRiskRule(
      CreditScoreProvider provider,
      @Value("${barrier.risk.credit-score-threshold:300}") int threshold,
      @Value("${barrier.risk.credit-score-low-score:70}") int score) {
    this.provider = provider;
    this.threshold = threshold;
    this.score = score;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    CreditScoreLookup lookup = provider.lookup(context.documentType(), context.documentDigits());
    if (lookup.score() == null || lookup.score() >= threshold) {
      return RiskResult.notApplicable("CREDIT_SCORE_LOW");
    }
    return new RiskResult(
        "CREDIT_SCORE_LOW",
        score,
        Severity.LOW,
        "Score de crédito externo abaixo do limiar (" + lookup.score() + "/" + threshold + ")",
        List.of("credit_score:" + lookup.score()),
        null);
  }

  @Override
  public String code() {
    return "CREDIT_SCORE_LOW";
  }
}
