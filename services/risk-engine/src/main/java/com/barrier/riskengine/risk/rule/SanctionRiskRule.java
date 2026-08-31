package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import java.util.Set;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import com.barrier.riskengine.screening.domain.enums.MatchBasis;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Sanção (OFAC/ONU/CGU). O desfecho depende de <b>como</b> o apontamento casou:
 *
 * <ul>
 *   <li>{@link MatchBasis#DOCUMENT} — CPF/CNPJ exato: identificação inequívoca, bloqueio;
 *   <li>{@link MatchBasis#NAME} — similaridade de nome: revisão humana obrigatória.
 * </ul>
 *
 * <p><b>Por que o match por nome não bloqueia:</b> a comparação é Jaro-Winkler sobre o nome
 * normalizado, sem data de nascimento, país ou qualquer qualificador de desempate. Um homônimo de
 * sancionado — e a lista SDN tem dezenas de milhares de nomes, muitos genéricos — era reprovado
 * automaticamente, sem revisão e sem recurso. Match por nome é indício: pontua alto, escala para
 * humano, não decide sozinho.
 *
 * <p>Quando os dois tipos aparecem juntos, o match por documento prevalece: já há identificação
 * inequívoca, o indício por nome não enfraquece isso.
 *
 * <p><b>Só o titular bloqueia.</b> Desde que o screening passou a consultar sócios e representante
 * legal, um apontamento pode se referir a outra entidade. Sócio do QSA vem sem documento (a Receita
 * não o publica), então na prática casa sempre por nome e já cairia em revisão — mas amarrar o
 * bloqueio ao titular é o que impede que um provedor de KYB futuro, trazendo o CPF do sócio,
 * transforme a regra em reprovação automática de uma PJ por sanção de terceiro.
 */
@Component
public class SanctionRiskRule implements RiskRule {

  private static final int DOCUMENT_MATCH_SCORE = 1000;
  private static final int NAME_MATCH_SCORE = 500;

  @Override
  public RiskResult evaluate(RiskContext context) {
    List<ScreeningHit> sanctions =
        context.screening().hits().stream().filter(h -> h.type() == MatchType.SANCTION).toList();

    if (sanctions.isEmpty()) {
      return RiskResult.notApplicable("SANCTION");
    }

    // Só o TITULAR bloqueia. Um sócio identificado por documento numa lista é um sinal forte, mas
    // a entidade sancionada é ele, não a empresa — reprovar a PJ automaticamente por isso seria
    // punir uma pessoa jurídica distinta, sem análise. Vai para revisão como qualquer indício.
    boolean byDocument =
        sanctions.stream().anyMatch(h -> h.basis() == MatchBasis.DOCUMENT && h.isTitular());
    List<String> evidences =
        sanctions.stream()
            .map(h -> h.party().label() + " · " + h.basis() + ":" + h.source() + ":" + h.matchedName())
            .toList();

    return byDocument
        ? new RiskResult(
            "SANCTION_HIT",
            DOCUMENT_MATCH_SCORE,
            Severity.CRITICAL,
            "Apontamento em lista de sanções por documento",
            evidences,
            RiskRecommendation.REJECT)
        : new RiskResult(
            "SANCTION_NAME_MATCH",
            NAME_MATCH_SCORE,
            Severity.HIGH,
            "Possível apontamento em lista de sanções por similaridade de nome — "
                + "revisão humana requerida (pode ser homônimo)",
            evidences,
            RiskRecommendation.REVIEW);
  }

  @Override
  public String code() {
    return "SANCTION";
  }

  @Override
  public Set<ContextInput> requires() {
    return Set.of(ContextInput.SCREENING);
  }
}
