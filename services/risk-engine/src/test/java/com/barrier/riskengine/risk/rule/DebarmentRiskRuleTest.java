package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.screening.domain.MatchBasis;
import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.ScreenedParty;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Inidoneidade em licitação não impede relacionamento bancário. Enquanto CEIS/CNEP eram
 * classificados como sanção, uma empresa inidônea recebia 1000 pontos e REJECT automático — recusa
 * de serviço a quem a lei não impede de ser cliente.
 */
class DebarmentRiskRuleTest {

  private final DebarmentRiskRule rule = new DebarmentRiskRule();

  private RiskContext context(ScreeningHit... hits) {
    return new RiskContext(
        "aid",
        "default",
        IdentityCheck.create("aid", IdentityStatus.VERIFIED, "bureau", "ok"),
        ScreeningResult.of("aid", List.of(hits)),
        null,
        null);
  }

  private static ScreeningHit hit(MatchBasis basis, ScreenedParty party) {
    return new ScreeningHit(
        MatchType.DEBARMENT, basis, party, "CEIS", "EMPRESA XPTO LTDA", "inidônea");
  }

  private static ScreenedParty titular() {
    return ScreenedParty.titular("EMPRESA XPTO LTDA", "11222333000181");
  }

  @Test
  void semApontamentoNaoAplica() {
    assertThat(rule.evaluate(context()).triggered()).isFalse();
  }

  /** O caso que motivou a separação: nunca recusa automática. */
  @Test
  void apontamentoPorDocumentoNoTitularPedeRevisaoENaoRecusa() {
    RiskResult r = rule.evaluate(context(hit(MatchBasis.DOCUMENT, titular())));

    assertThat(r.triggered()).isTrue();
    assertThat(r.ruleCode()).isEqualTo("DEBARMENT_HIT");
    assertThat(r.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(r.score()).isEqualTo(200);
  }

  /** Match por nome é indício fraco: pontua pouco e não muda a recomendação sozinho. */
  @Test
  void apontamentoPorNomeApenasPontua() {
    RiskResult r = rule.evaluate(context(hit(MatchBasis.NAME, titular())));

    assertThat(r.triggered()).isTrue();
    assertThat(r.ruleCode()).isEqualTo("DEBARMENT_NAME_MATCH");
    assertThat(r.recommendation()).isEqualTo(RiskRecommendation.APPROVE);
    assertThat(r.score()).isEqualTo(100);
  }

  /** A entidade punida é o sócio, não a empresa avaliada: não escala por causa dele. */
  @Test
  void apontamentoDeSocioNaoPedeRevisao() {
    ScreenedParty socio = ScreenedParty.socio("FULANO DE TAL");

    RiskResult r = rule.evaluate(context(hit(MatchBasis.DOCUMENT, socio)));

    assertThat(r.recommendation()).isEqualTo(RiskRecommendation.APPROVE);
    assertThat(r.ruleCode()).isEqualTo("DEBARMENT_NAME_MATCH");
  }

  /** A evidência tem que dizer de quem é o apontamento e de que lista veio. */
  @Test
  void evidenciaIdentificaParteEFonte() {
    RiskResult r = rule.evaluate(context(hit(MatchBasis.DOCUMENT, titular())));

    assertThat(r.evidences()).singleElement().asString().contains("CEIS", "EMPRESA XPTO LTDA");
  }
}
