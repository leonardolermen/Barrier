package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoryRiskRuleTest {

  private static final HistoryRiskRule RULE = new HistoryRiskRule(80, 60, 300, 400);

  private RiskContext context(List<String> historyEventTypes) {
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
        historyEventTypes);
  }

  @Test
  void semHistoricoNaoAplica() {
    assertThat(RULE.evaluate(context(List.of())).triggered()).isFalse();
  }

  @Test
  void chargebackPontuaComSeveridadeMedia() {
    RiskResult r = RULE.evaluate(context(List.of("CHARGEBACK")));

    assertThat(r.triggered()).isTrue();
    assertThat(r.score()).isEqualTo(80);
    assertThat(r.severity()).isEqualTo(Severity.MEDIUM);
  }

  @Test
  void fraudReportPontuaComSeveridadeCritica() {
    RiskResult r = RULE.evaluate(context(List.of("FRAUD_REPORT")));

    assertThat(r.score()).isEqualTo(300);
    assertThat(r.severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void variosEventosSomam() {
    RiskResult r = RULE.evaluate(context(List.of("CHARGEBACK", "PIX_RETURNED")));

    assertThat(r.score()).isEqualTo(140);
    assertThat(r.evidences()).hasSize(2);
  }
}
