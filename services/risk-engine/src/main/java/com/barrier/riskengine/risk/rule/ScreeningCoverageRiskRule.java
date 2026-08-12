package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.watchlist.WatchlistImportStatus;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Impede aprovação automática quando o screening rodou <b>sem cobertura</b>.
 *
 * <p>Um {@code ScreeningResult} vazio é ambíguo: pode significar "consultamos as listas e o cliente
 * está limpo" ou "não havia lista nenhuma para consultar". O motor tratava os dois casos como o
 * primeiro. Se a importação da CGU ou da OFAC falha — portal fora do ar, CSV com layout novo, ZIP
 * truncado — a tabela fica vazia, todo screening responde CLEAR e toda avaliação é aprovada, com a
 * trilha registrando "sem apontamentos". É a diferença entre ausência de evidência e evidência de
 * ausência, e ela decide se alguém sancionado abre conta.
 *
 * <p>Força REVIEW em vez de REJECT: o problema é nosso, não do cliente. A avaliação vai para
 * análise humana e pode ser reprocessada quando a cobertura voltar.
 */
@Component
public class ScreeningCoverageRiskRule implements RiskRule {

  private static final String RULE_CODE = "SCREENING_COVERAGE";
  private static final Set<MatchType> REQUIRED = Set.of(MatchType.SANCTION, MatchType.PEP);
  private static final int SCORE = 300;

  private final WatchlistImportStatus status;

  public ScreeningCoverageRiskRule(WatchlistImportStatus status) {
    this.status = status;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    Set<MatchType> covered = status.coverage();
    List<String> missing =
        REQUIRED.stream().filter(type -> !covered.contains(type)).map(Enum::name).sorted().toList();

    if (missing.isEmpty()) {
      return RiskResult.notApplicable(RULE_CODE);
    }
    return new RiskResult(
        RULE_CODE,
        SCORE,
        Severity.HIGH,
        "Screening executado sem cobertura de " + String.join(", ", missing),
        missing.stream().map(type -> "cobertura ausente:" + type).toList(),
        RiskRecommendation.REVIEW);
  }

  @Override
  public String code() {
    return RULE_CODE;
  }
}
