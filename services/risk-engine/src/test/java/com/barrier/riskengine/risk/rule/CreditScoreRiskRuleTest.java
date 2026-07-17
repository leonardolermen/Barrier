package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.credit.client.CreditScoreLookup;
import com.barrier.riskengine.credit.client.CreditScoreProvider;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreditScoreRiskRuleTest {

  private RiskContext context() {
    return new RiskContext(
        "aid",
        "default",
        IdentityCheck.create("aid", IdentityStatus.VERIFIED, "stub", "ok"),
        ScreeningResult.of("aid", List.of()),
        null,
        null,
        null,
        0,
        0,
        "CPF",
        "11144477735",
        List.of());
  }

  @Test
  void scoreAbaixoDoLimiarPontua() {
    var rule = new CreditScoreRiskRule((dt, dd) -> new CreditScoreLookup(150), 300, 70);

    RiskResult r = rule.evaluate(context());

    assertThat(r.triggered()).isTrue();
    assertThat(r.score()).isEqualTo(70);
    assertThat(r.evidences()).anyMatch(e -> e.contains("150"));
  }

  @Test
  void scoreAcimaDoLimiarNaoAplica() {
    CreditScoreProvider provider = (dt, dd) -> new CreditScoreLookup(800);
    var rule = new CreditScoreRiskRule(provider, 300, 70);

    assertThat(rule.evaluate(context()).triggered()).isFalse();
  }

  @Test
  void semScoreNaoAplica() {
    var rule = new CreditScoreRiskRule((dt, dd) -> CreditScoreLookup.UNKNOWN, 300, 70);

    assertThat(rule.evaluate(context()).triggered()).isFalse();
  }
}
