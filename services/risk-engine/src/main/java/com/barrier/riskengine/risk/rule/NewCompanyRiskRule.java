package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import com.barrier.riskengine.tenant.config.service.TenantRiskConfigService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Empresa recém-aberta é um fator de risco na abordagem baseada em risco (menor histórico, uso
 * comum em fraude/laranja). Pontua sem forçar recomendação — o motor decide pela banda.
 *
 * <p>Janela e score são configuráveis por tenant via {@link TenantRiskConfigService} (regra
 * {@code NEW_COMPANY}); {@code barrier.risk.new-company.months}/{@code .score} seguem como
 * default global quando o tenant não tem override. O {@link Clock} é injetável para testes
 * determinísticos.
 */
@Component
public class NewCompanyRiskRule implements RiskRule {

  private static final String RULE_CODE = "NEW_COMPANY";

  private final int defaultMonths;
  private final int defaultScore;
  private final Clock clock;
  private final TenantRiskConfigService tenantConfig;

  public NewCompanyRiskRule(
      @Value("${barrier.risk.new-company.months:6}") int defaultMonths,
      @Value("${barrier.risk.new-company.score:150}") int defaultScore,
      Clock clock,
      TenantRiskConfigService tenantConfig) {
    this.defaultMonths = defaultMonths;
    this.defaultScore = defaultScore;
    this.clock = clock;
    this.tenantConfig = tenantConfig;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    CompanyProfile company = context.company();
    if (company == null || company.openingDate() == null) {
      return RiskResult.notApplicable(RULE_CODE);
    }
    int months = tenantConfig.getInt(context.tenantId(), RULE_CODE, "months", defaultMonths);
    int score = tenantConfig.getInt(context.tenantId(), RULE_CODE, "score", defaultScore);
    LocalDate cutoff = LocalDate.now(clock).minusMonths(months);
    if (company.openingDate().isAfter(cutoff)) {
      return new RiskResult(
          RULE_CODE,
          score,
          Severity.MEDIUM,
          "Empresa aberta há menos de " + months + " meses",
          List.of("abertura:" + company.openingDate(), "config:months=" + months),
          null);
    }
    return RiskResult.notApplicable(RULE_CODE);
  }

  @Override
  public String code() {
    return "NEW_COMPANY";
  }
}
