package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeviceReuseRiskRuleTest {

  private RiskContext context(long reuseCount) {
    return new RiskContext(
        "aid",
        "default",
        IdentityCheck.create("aid", IdentityStatus.VERIFIED, "stub", "ok"),
        ScreeningResult.of("aid", List.of()),
        null,
        null,
        null,
        reuseCount);
  }

  @Test
  void abaixoDoLimiarNaoAplica() {
    var rule = new DeviceReuseRiskRule(3, 120);

    assertThat(rule.evaluate(context(2)).triggered()).isFalse();
  }

  @Test
  void noLimiarPontua() {
    var rule = new DeviceReuseRiskRule(3, 120);

    RiskResult r = rule.evaluate(context(3));

    assertThat(r.triggered()).isTrue();
    assertThat(r.score()).isEqualTo(120);
    assertThat(r.evidences()).anyMatch(e -> e.contains("device_reuse_count:3"));
  }

  @Test
  void acimaDoLimiarPontua() {
    var rule = new DeviceReuseRiskRule(3, 120);

    assertThat(rule.evaluate(context(10)).triggered()).isTrue();
  }
}
