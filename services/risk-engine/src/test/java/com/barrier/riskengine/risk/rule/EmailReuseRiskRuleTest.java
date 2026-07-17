package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmailReuseRiskRuleTest {

  private RiskContext context(long emailReuseCount) {
    return new RiskContext(
        "aid",
        "default",
        IdentityCheck.create("aid", IdentityStatus.VERIFIED, "stub", "ok"),
        ScreeningResult.of("aid", List.of()),
        null,
        null,
        null,
        0,
        emailReuseCount);
  }

  @Test
  void abaixoDoLimiarNaoAplica() {
    var rule = new EmailReuseRiskRule(2, 100);

    assertThat(rule.evaluate(context(1)).triggered()).isFalse();
  }

  @Test
  void noLimiarPontua() {
    var rule = new EmailReuseRiskRule(2, 100);

    RiskResult r = rule.evaluate(context(2));

    assertThat(r.triggered()).isTrue();
    assertThat(r.score()).isEqualTo(100);
    assertThat(r.evidences()).anyMatch(e -> e.contains("email_reuse_count:2"));
  }
}
