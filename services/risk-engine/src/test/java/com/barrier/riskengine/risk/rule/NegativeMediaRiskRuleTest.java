package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.screening.domain.MatchBasis;
import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class NegativeMediaRiskRuleTest {

  private RiskContext context(ScreeningHit... hits) {
    return new RiskContext(
        "aid",
        "default",
        IdentityCheck.create("aid", IdentityStatus.VERIFIED, "stub", "ok"),
        ScreeningResult.of("aid", List.of(hits)),
        null,
        null);
  }

  @Test
  void apontamentoDeMidiaNegativaForcaRevisao() {
    var rule = new NegativeMediaRiskRule(250);

    RiskResult r =
        rule.evaluate(
            context(new ScreeningHit(MatchType.ADVERSE_MEDIA, MatchBasis.NAME, "stub-negative-media", "F", "fraude")));

    assertThat(r.triggered()).isTrue();
    assertThat(r.score()).isEqualTo(250);
    assertThat(r.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
  }

  @Test
  void semApontamentoNaoAplica() {
    var rule = new NegativeMediaRiskRule(250);

    assertThat(rule.evaluate(context()).triggered()).isFalse();
  }

  @Test
  void ignoraApontamentosDeOutrosTipos() {
    var rule = new NegativeMediaRiskRule(250);

    RiskResult r = rule.evaluate(context(new ScreeningHit(MatchType.PEP, MatchBasis.NAME, "base", "F", "cargo")));

    assertThat(r.triggered()).isFalse();
  }
}
