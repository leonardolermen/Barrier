package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.screening.domain.MatchBasis;
import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Inidoneidade ou impedimento de contratar com a administração pública (CEIS/CNEP).
 *
 * <p><b>Alerta, não bloqueio.</b> Enquanto CEIS/CNEP eram classificados como sanção, uma empresa
 * inidônea em licitação recebia 1000 pontos e {@code REJECT} automático — recusa de relacionamento
 * bancário a quem a lei não impede de ser cliente. Inidoneidade é impedimento perante a
 * administração pública, não sanção financeira; nenhuma norma do Bacen manda recusar conta por
 * isso, e recusar é negação de serviço a uma empresa legalmente apta.
 *
 * <p>O apontamento continua na trilha e continua pesando: é informação reputacional legítima para
 * PLD-FT, e some-se a outros fatores pode levar a avaliação à revisão pela banda de score. O que
 * deixou de existir é o caminho direto para a recusa.
 *
 * <p>Um caso escapa do "só alerta": {@link MatchBasis#DOCUMENT} sobre o <b>titular</b> — CNPJ
 * exato, sem ambiguidade de homônimo. Aí a recomendação é revisão humana, para o analista decidir
 * com o contexto do negócio. Nunca recusa automática, e nunca por apontamento de sócio: a entidade
 * punida é ele, não a empresa que está sendo avaliada.
 */
@Component
public class DebarmentRiskRule implements RiskRule {

  private static final String RULE_CODE = "DEBARMENT";
  private static final int DOCUMENT_MATCH_SCORE = 200;
  private static final int NAME_MATCH_SCORE = 100;

  @Override
  public RiskResult evaluate(RiskContext context) {
    List<ScreeningHit> hits =
        context.screening().hits().stream().filter(h -> h.type() == MatchType.DEBARMENT).toList();

    if (hits.isEmpty()) {
      return RiskResult.notApplicable(RULE_CODE);
    }

    boolean byDocument =
        hits.stream().anyMatch(h -> h.basis() == MatchBasis.DOCUMENT && h.isTitular());
    List<String> evidences =
        hits.stream()
            .map(h -> h.party().label() + " · " + h.basis() + ":" + h.source() + ":" + h.matchedName())
            .toList();

    return byDocument
        ? new RiskResult(
            "DEBARMENT_HIT",
            DOCUMENT_MATCH_SCORE,
            Severity.MEDIUM,
            "Inidoneidade/impedimento de contratar com a administração pública (identificação por "
                + "documento) — não impede relacionamento, mas exige análise",
            evidences,
            RiskRecommendation.REVIEW)
        : new RiskResult(
            "DEBARMENT_NAME_MATCH",
            NAME_MATCH_SCORE,
            Severity.LOW,
            "Possível inidoneidade/impedimento por similaridade de nome (pode ser homônimo)",
            evidences,
            RiskRecommendation.APPROVE);
  }

  @Override
  public String code() {
    return RULE_CODE;
  }
}
