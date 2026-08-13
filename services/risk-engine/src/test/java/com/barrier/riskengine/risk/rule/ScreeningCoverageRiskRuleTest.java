package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.watchlist.WatchlistImportStatus;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScreeningCoverageRiskRuleTest {

  @Mock WatchlistImportStatus status;

  private RiskResult evaluate() {
    return new ScreeningCoverageRiskRule(status)
        .evaluate(new RiskContext("aid", "default", null, null, null, null, null));
  }

  @Test
  void comCoberturaCompletaNaoSeAplica() {
    when(status.coverage())
        .thenReturn(Set.of(MatchType.SANCTION, MatchType.PEP, MatchType.ADVERSE_MEDIA));

    assertThat(evaluate().triggered()).isFalse();
  }

  /**
   * Regressão do cenário em que a importação falha e a tabela fica vazia: screening responde CLEAR
   * para todos e o motor aprovava, registrando "sem apontamentos" na trilha.
   */
  @Test
  void semNenhumaCoberturaForcaRevisao() {
    when(status.coverage()).thenReturn(Set.of());

    RiskResult result = evaluate();

    assertThat(result.triggered()).isTrue();
    assertThat(result.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(result.reason()).contains("PEP").contains("SANCTION").contains("ADVERSE_MEDIA");
  }

  @Test
  void coberturaParcialTambemForcaRevisaoEDizOQueFalta() {
    when(status.coverage()).thenReturn(Set.of(MatchType.SANCTION, MatchType.ADVERSE_MEDIA));

    RiskResult result = evaluate();

    assertThat(result.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(result.reason()).contains("PEP").doesNotContain("SANCTION");
    assertThat(result.evidences()).containsExactly("cobertura ausente:PEP");
  }

  /**
   * Regressão desta branch: ADVERSE_MEDIA agora é obrigatória. Sem esta checagem, o único provedor
   * (StubNegativeMediaProvider, CSV vazio por padrão em prod) deixava NegativeMediaRiskRule inerte
   * sem nenhum registro de que mídia negativa nunca foi consultada de verdade.
   */
  @Test
  void semCoberturaDeMidiaNegativaForcaRevisao() {
    when(status.coverage()).thenReturn(Set.of(MatchType.SANCTION, MatchType.PEP));

    RiskResult result = evaluate();

    assertThat(result.triggered()).isTrue();
    assertThat(result.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(result.evidences()).containsExactly("cobertura ausente:ADVERSE_MEDIA");
  }
}
