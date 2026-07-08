package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Empresa recém-aberta é um fator de risco na abordagem baseada em risco (menor histórico, uso
 * comum em fraude/laranja). Pontua sem forçar recomendação — o motor decide pela banda.
 *
 * <p>Janela configurável em {@code barrier.risk.new-company.months} (padrão 6). O {@link Clock} é
 * injetável para testes determinísticos.
 */
@Component
public class NewCompanyRiskRule implements RiskRule {

  private final int months;
  private final int score;
  private final Clock clock;

  public NewCompanyRiskRule(
      @Value("${barrier.risk.new-company.months:6}") int months,
      @Value("${barrier.risk.new-company.score:150}") int score,
      Clock clock) {
    this.months = months;
    this.score = score;
    this.clock = clock;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    CompanyProfile company = context.company();
    if (company == null || company.openingDate() == null) {
      return RiskResult.notApplicable("NEW_COMPANY");
    }
    LocalDate cutoff = LocalDate.now(clock).minusMonths(months);
    if (company.openingDate().isAfter(cutoff)) {
      return new RiskResult(
          "NEW_COMPANY",
          score,
          Severity.MEDIUM,
          "Empresa aberta há menos de " + months + " meses",
          List.of("abertura:" + company.openingDate()),
          null);
    }
    return RiskResult.notApplicable("NEW_COMPANY");
  }
}
