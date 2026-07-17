package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Consistência entre os dados cadastrados: hoje só compara o DDD do telefone com o estado do
 * endereço (plano de numeração ANATEL) — sinal barato, sem depender de provedor externo. Não
 * força recomendação: telefone de outra UF é comum (mudança, portabilidade) e sozinho não deve
 * bloquear nem exigir revisão, só somar ao score.
 */
@Component
public class ConsistencyRiskRule implements RiskRule {

  private final int score;

  public ConsistencyRiskRule(@Value("${barrier.risk.consistency-score:60}") int score) {
    this.score = score;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    SubjectProfile profile = context.profile();
    if (profile == null || profile.phone() == null || profile.address() == null) {
      return RiskResult.notApplicable("PHONE_ADDRESS_MISMATCH");
    }
    String phoneUf = PhoneAreaCode.ufOf(profile.phone());
    String addressUf = profile.address().state();
    if (phoneUf == null || addressUf == null || phoneUf.equalsIgnoreCase(addressUf)) {
      return RiskResult.notApplicable("PHONE_ADDRESS_MISMATCH");
    }
    return new RiskResult(
        "PHONE_ADDRESS_MISMATCH",
        score,
        Severity.LOW,
        "DDD do telefone (" + phoneUf + ") diferente do estado do endereço (" + addressUf + ")",
        List.of("phone_uf:" + phoneUf, "address_uf:" + addressUf),
        null);
  }

  @Override
  public String code() {
    return "PHONE_ADDRESS_MISMATCH";
  }
}
