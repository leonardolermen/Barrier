package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * CNAE sensível: atividades com maior exposição a PLD-FT (câmbio/remessas, cripto, jogos,
 * metais/pedras preciosas, factoring). Quando o CNAE principal da PJ cai na lista, pontua como
 * fator de atenção (sem forçar recomendação — a banda decide).
 *
 * <p>A lista é configurável em {@code barrier.risk.sensitive-cnae} (CSV de códigos de 7 dígitos);
 * o padrão cobre um conjunto inicial conhecido.
 */
@Component
public class SensitiveCnaeRiskRule implements RiskRule {

  private final Set<String> sensitiveCnae;
  private final int score;

  public SensitiveCnaeRiskRule(
      @Value(
              "${barrier.risk.sensitive-cnae:6619302,6612605,6499999,9200301,9200302,9200399,4683401,3211601,4713002,6440900}")
          Set<String> sensitiveCnae,
      @Value("${barrier.risk.sensitive-cnae-score:200}") int score) {
    this.sensitiveCnae = sensitiveCnae;
    this.score = score;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    CompanyProfile company = context.company();
    if (company == null || company.cnaeCode() == null || !sensitiveCnae.contains(company.cnaeCode())) {
      return RiskResult.notApplicable("SENSITIVE_CNAE");
    }
    return new RiskResult(
        "SENSITIVE_CNAE",
        score,
        Severity.MEDIUM,
        "CNAE principal em atividade sensível a PLD-FT",
        List.of("cnae:" + company.cnaeCode() + " " + nullSafe(company.cnaeDescription())),
        null);
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }
}
