package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * O mesmo device (fingerprint/deviceId) usado em várias contas recentemente é um padrão
 * clássico de fraude por múltiplas contas — pontua, não bloqueia sozinho (pode ser uso
 * legítimo, ex.: um dispositivo compartilhado por uma família).
 */
@Component
public class DeviceReuseRiskRule implements RiskRule {

  private final long threshold;
  private final int score;

  public DeviceReuseRiskRule(
      @Value("${barrier.risk.device-reuse-threshold:3}") long threshold,
      @Value("${barrier.risk.device-reuse-score:120}") int score) {
    this.threshold = threshold;
    this.score = score;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    if (context.deviceReuseCount() < threshold) {
      return RiskResult.notApplicable("DEVICE_REUSE");
    }
    return new RiskResult(
        "DEVICE_REUSE",
        score,
        Severity.MEDIUM,
        "Mesmo device usado em " + context.deviceReuseCount() + " cadastros recentes",
        List.of("device_reuse_count:" + context.deviceReuseCount()),
        null);
  }

  @Override
  public String code() {
    return "DEVICE_REUSE";
  }
}
