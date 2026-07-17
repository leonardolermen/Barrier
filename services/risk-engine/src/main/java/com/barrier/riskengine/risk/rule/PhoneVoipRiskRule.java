package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.phone.client.PhoneLookup;
import com.barrier.riskengine.phone.client.PhoneProvider;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Telefone VoIP é mais fácil de descartar/trocar que uma linha móvel real — fator de atenção,
 * não bloqueia sozinho.
 */
@Component
public class PhoneVoipRiskRule implements RiskRule {

  private final PhoneProvider phoneProvider;
  private final int score;

  public PhoneVoipRiskRule(
      PhoneProvider phoneProvider, @Value("${barrier.risk.phone-voip-score:50}") int score) {
    this.phoneProvider = phoneProvider;
    this.score = score;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    SubjectProfile profile = context.profile();
    if (profile == null || profile.phone() == null) {
      return RiskResult.notApplicable("PHONE_VOIP");
    }
    PhoneLookup lookup = phoneProvider.lookup(profile.phone());
    if (!lookup.voip()) {
      return RiskResult.notApplicable("PHONE_VOIP");
    }
    return new RiskResult(
        "PHONE_VOIP", score, Severity.LOW, "Telefone cadastrado é VoIP", List.of("phone:voip"), null);
  }

  @Override
  public String code() {
    return "PHONE_VOIP";
  }
}
