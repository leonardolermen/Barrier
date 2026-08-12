package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import com.barrier.riskengine.tenant.config.service.TenantRiskConfigService;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * CNAE sensível: atividades com maior exposição a PLD-FT (câmbio/remessas, cripto, jogos,
 * metais/pedras preciosas, factoring). Quando o CNAE principal da PJ cai na lista, pontua como
 * fator de atenção (sem forçar recomendação — a banda decide).
 *
 * <p>A lista base é configurável em {@code barrier.risk.sensitive-cnae} (CSV de códigos de 7
 * dígitos) e o score em {@code barrier.risk.sensitive-cnae-score}; um tenant pode acrescentar
 * CNAEs e ajustar o score via {@link TenantRiskConfigService} (regra {@code SENSITIVE_CNAE}) —
 * o override de CNAEs sempre se soma ao conjunto base, nunca o substitui.
 */
@Component
public class SensitiveCnaeRiskRule implements RiskRule {

  private static final String RULE_CODE = "SENSITIVE_CNAE";

  private final Set<String> defaultSensitiveCnae;
  private final int defaultScore;
  private final TenantRiskConfigService tenantConfig;

  public SensitiveCnaeRiskRule(
      @Value(
              "${barrier.risk.sensitive-cnae:6619302,6612605,6499999,9200301,9200302,9200399,4683401,3211601,4713002,6440900}")
          Set<String> defaultSensitiveCnae,
      @Value("${barrier.risk.sensitive-cnae-score:200}") int defaultScore,
      TenantRiskConfigService tenantConfig) {
    this.defaultSensitiveCnae = defaultSensitiveCnae;
    this.defaultScore = defaultScore;
    this.tenantConfig = tenantConfig;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    CompanyProfile company = context.company();
    if (company == null || company.cnaeCode() == null) {
      return RiskResult.notApplicable(RULE_CODE);
    }
    Set<String> sensitiveCnae =
        tenantConfig.getStringSet(
            context.tenantId(), RULE_CODE, "cnae-codes", defaultSensitiveCnae);
    if (!sensitiveCnae.contains(company.cnaeCode())) {
      return RiskResult.notApplicable(RULE_CODE);
    }
    int score = tenantConfig.getInt(context.tenantId(), RULE_CODE, "score", defaultScore);
    return new RiskResult(
        RULE_CODE,
        score,
        Severity.MEDIUM,
        "CNAE principal em atividade sensível a PLD-FT",
        List.of(
            "cnae:" + company.cnaeCode() + " " + nullSafe(company.cnaeDescription()),
            "config:score=" + score),
        null);
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }

  @Override
  public String code() {
    return "SENSITIVE_CNAE";
  }
}
