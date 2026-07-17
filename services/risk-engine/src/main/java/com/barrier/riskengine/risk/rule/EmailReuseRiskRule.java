package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * O mesmo email usado no cadastro de vários subjects é um sinal de múltiplas contas (fraude/
 * laranja) — mesmo espírito de {@link DeviceReuseRiskRule}, mas por email em vez de device.
 */
@Component
public class EmailReuseRiskRule implements RiskRule {

  private final long threshold;
  private final int score;

  public EmailReuseRiskRule(
      @Value("${barrier.risk.email-reuse-threshold:2}") long threshold,
      @Value("${barrier.risk.email-reuse-score:100}") int score) {
    this.threshold = threshold;
    this.score = score;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    if (context.emailReuseCount() < threshold) {
      return RiskResult.notApplicable("EMAIL_REUSE");
    }
    return new RiskResult(
        "EMAIL_REUSE",
        score,
        Severity.MEDIUM,
        "Mesmo email usado em outros " + context.emailReuseCount() + " cadastro(s)",
        List.of("email_reuse_count:" + context.emailReuseCount()),
        null);
  }

  @Override
  public String code() {
    return "EMAIL_REUSE";
  }
}
