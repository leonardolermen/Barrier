package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import java.util.ArrayList;
import java.util.List;

import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import java.util.Set;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import org.springframework.stereotype.Component;

/**
 * Risco do quadro societário de PJ (KYB), a partir do QSA direto trazido pelo bureau.
 *
 * <ul>
 *   <li>sócio estrangeiro → fator de peso alto (dificulta a identificação do beneficiário final);
 *   <li>sócio PJ (holding/participação encadeada) → fator médio (estrutura a aprofundar).
 * </ul>
 *
 * <p>Escopo atual: 1º grau (QSA direto da Receita). A navegação da árvore de participação até o
 * 3º grau depende de um provedor de KYB dedicado e fica para a fase seguinte.
 */
@Component
public class CorporateStructureRiskRule implements RiskRule {

  private static final int FOREIGN_PARTNER_SCORE = 200;
  private static final int LEGAL_ENTITY_PARTNER_SCORE = 100;
  private static final int MAX_SCORE = 300;

  @Override
  public RiskResult evaluate(RiskContext context) {
    CompanyProfile company = context.company();
    if (company == null || company.partners().isEmpty()) {
      return RiskResult.notApplicable("CORPORATE_STRUCTURE");
    }

    List<String> foreign =
        company.partners().stream()
            .filter(CompanyProfile.Partner::foreign)
            .map(p -> "sócio estrangeiro:" + p.name())
            .toList();
    List<String> legalEntities =
        company.partners().stream()
            .filter(CompanyProfile.Partner::legalEntity)
            .map(p -> "sócio PJ:" + p.name())
            .toList();

    if (foreign.isEmpty() && legalEntities.isEmpty()) {
      return RiskResult.notApplicable("CORPORATE_STRUCTURE");
    }

    int score = 0;
    List<String> evidences = new ArrayList<>();
    if (!foreign.isEmpty()) {
      score += FOREIGN_PARTNER_SCORE;
      evidences.addAll(foreign);
    }
    if (!legalEntities.isEmpty()) {
      score += LEGAL_ENTITY_PARTNER_SCORE;
      evidences.addAll(legalEntities);
    }

    Severity severity = foreign.isEmpty() ? Severity.MEDIUM : Severity.HIGH;
    return new RiskResult(
        "CORPORATE_STRUCTURE",
        Math.min(MAX_SCORE, score),
        severity,
        "Quadro societário com fatores de atenção (KYB)",
        evidences,
        null);
  }

  @Override
  public String code() {
    return "CORPORATE_STRUCTURE";
  }

  @Override
  public Set<ContextInput> requires() {
    return Set.of(ContextInput.COMPANY);
  }
}
