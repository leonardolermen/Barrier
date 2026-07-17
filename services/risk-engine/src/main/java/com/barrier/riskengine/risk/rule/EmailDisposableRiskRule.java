package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.email.client.EmailLookup;
import com.barrier.riskengine.email.client.EmailProvider;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Email de domínio descartável (temp-mail, mailinator, etc.) indica baixo comprometimento com o
 * cadastro — comum em contas fraudulentas/testadas em massa. Pontua, não bloqueia sozinho.
 */
@Component
public class EmailDisposableRiskRule implements RiskRule {

  private final EmailProvider emailProvider;
  private final int score;

  public EmailDisposableRiskRule(
      EmailProvider emailProvider, @Value("${barrier.risk.email-disposable-score:90}") int score) {
    this.emailProvider = emailProvider;
    this.score = score;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    SubjectProfile profile = context.profile();
    if (profile == null || profile.email() == null) {
      return RiskResult.notApplicable("EMAIL_DISPOSABLE");
    }
    EmailLookup lookup = emailProvider.lookup(profile.email());
    if (!lookup.disposableDomain()) {
      return RiskResult.notApplicable("EMAIL_DISPOSABLE");
    }
    return new RiskResult(
        "EMAIL_DISPOSABLE",
        score,
        Severity.MEDIUM,
        "Email de domínio descartável",
        List.of("email:disposable"),
        null);
  }

  @Override
  public String code() {
    return "EMAIL_DISPOSABLE";
  }
}
