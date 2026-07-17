package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Histórico interno do subject (chargeback, PIX devolvido, denúncia, conta encerrada por
 * fraude) — cada tipo de evento tem um peso próprio; a soma pontua, com severidade proporcional
 * ao evento mais grave já registrado.
 */
@Component
public class HistoryRiskRule implements RiskRule {

  private final Map<String, Integer> weights;

  public HistoryRiskRule(
      @Value("${barrier.risk.history.chargeback-score:80}") int chargebackScore,
      @Value("${barrier.risk.history.pix-returned-score:60}") int pixReturnedScore,
      @Value("${barrier.risk.history.fraud-report-score:300}") int fraudReportScore,
      @Value("${barrier.risk.history.account-closed-fraud-score:400}") int accountClosedScore) {
    this.weights =
        Map.of(
            "CHARGEBACK", chargebackScore,
            "PIX_RETURNED", pixReturnedScore,
            "FRAUD_REPORT", fraudReportScore,
            "ACCOUNT_CLOSED_FRAUD", accountClosedScore);
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    List<String> events = context.historyEventTypes();
    if (events.isEmpty()) {
      return RiskResult.notApplicable("HISTORY");
    }
    int total = 0;
    int worst = 0;
    List<String> evidences = new ArrayList<>();
    for (String eventType : events) {
      int weight = weights.getOrDefault(eventType, 0);
      total += weight;
      worst = Math.max(worst, weight);
      evidences.add("history:" + eventType);
    }
    if (total == 0) {
      return RiskResult.notApplicable("HISTORY");
    }
    Severity severity = worst >= 300 ? Severity.CRITICAL : worst >= 100 ? Severity.HIGH : Severity.MEDIUM;
    return new RiskResult(
        "HISTORY",
        Math.min(total, 1000),
        severity,
        "Histórico interno com " + events.size() + " evento(s) de atenção",
        evidences,
        null);
  }

  @Override
  public String code() {
    return "HISTORY";
  }
}
