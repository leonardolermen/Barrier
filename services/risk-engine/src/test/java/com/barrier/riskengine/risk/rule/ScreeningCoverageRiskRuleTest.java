package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.client.interfaces.NegativeMediaProvider;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.watchlist.WatchlistImportStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScreeningCoverageRiskRuleTest {

  @Mock WatchlistImportStatus status;

  /**
   * Construtor de 1 argumento: "nenhum provedor de mídia negativa autoritativo" — o cenário mais
   * comum hoje (só o {@code StubNegativeMediaProvider} existe). Exige só SANCTION/PEP.
   */
  private RiskResult evaluateSemProvedorDeMidia() {
    return new ScreeningCoverageRiskRule(status)
        .evaluate(new RiskContext("aid", "default", null, null, null, null, null));
  }

  private RiskResult evaluateComProvedores(List<NegativeMediaProvider> providers) {
    return new ScreeningCoverageRiskRule(status, providers)
        .evaluate(new RiskContext("aid", "default", null, null, null, null, null));
  }

  @Test
  void comCoberturaCompletaNaoSeAplica() {
    when(status.coverage()).thenReturn(Set.of(MatchType.SANCTION, MatchType.PEP));

    assertThat(evaluateSemProvedorDeMidia().triggered()).isFalse();
  }

  /**
   * Regressão do cenário em que a importação falha e a tabela fica vazia: screening responde CLEAR
   * para todos e o motor aprovava, registrando "sem apontamentos" na trilha.
   */
  @Test
  void semNenhumaCoberturaForcaRevisao() {
    when(status.coverage()).thenReturn(Set.of());

    RiskResult result = evaluateSemProvedorDeMidia();

    assertThat(result.triggered()).isTrue();
    assertThat(result.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(result.reason()).contains("PEP").contains("SANCTION");
  }

  @Test
  void coberturaParcialTambemForcaRevisaoEDizOQueFalta() {
    when(status.coverage()).thenReturn(Set.of(MatchType.SANCTION));

    RiskResult result = evaluateSemProvedorDeMidia();

    assertThat(result.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(result.reason()).contains("PEP").doesNotContain("SANCTION");
    assertThat(result.evidences()).containsExactly("cobertura ausente:PEP");
  }

  /**
   * Sem provedor de mídia negativa contratado (só o construtor de 1 argumento, ou uma lista só com
   * providers não autoritativos), ADVERSE_MEDIA nunca entra na exigência — mesmo com cobertura de
   * SANCTION/PEP completa e nenhuma fonte de mídia negativa. Alarme por avaliação, aqui, não
   * informaria nada: a ausência é conhecida, constante, e vale para toda a base.
   */
  @Test
  void semProvedorAutoritativoNaoPontuaPorFaltaDeAdverseMedia() {
    when(status.coverage()).thenReturn(Set.of(MatchType.SANCTION, MatchType.PEP));

    assertThat(evaluateSemProvedorDeMidia().triggered()).isFalse();
  }

  /**
   * Mesmo com o construtor de 2 argumentos, uma lista só com providers não autoritativos (o
   * {@code StubNegativeMediaProvider} de verdade tem {@code authoritative() == false}) se comporta
   * como "nenhum provedor" — não pontua.
   */
  @Test
  void soComProvedorStubNaoPontua() {
    when(status.coverage()).thenReturn(Set.of(MatchType.SANCTION, MatchType.PEP));
    NegativeMediaProvider stub = fakeProvider("stub-negative-media", false);

    assertThat(evaluateComProvedores(List.of(stub)).triggered()).isFalse();
  }

  /**
   * Regressão desta branch: com um provedor autoritativo configurado, ADVERSE_MEDIA passa a ser
   * exigida como SANCTION/PEP — é controle que deveria estar rodando. Se a distinção entre
   * autoritativo e stub for removida, este teste fica indistinguível do de cima e qualquer um dos
   * dois quebra.
   */
  @Test
  void comProvedorAutoritativoSemCoberturaForcaRevisao() {
    when(status.coverage()).thenReturn(Set.of(MatchType.SANCTION, MatchType.PEP));
    NegativeMediaProvider contratado = fakeProvider("lexisnexis", true);

    RiskResult result = evaluateComProvedores(List.of(contratado));

    assertThat(result.triggered()).isTrue();
    assertThat(result.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(result.evidences()).containsExactly("cobertura ausente:ADVERSE_MEDIA");
  }

  private static NegativeMediaProvider fakeProvider(String name, boolean authoritative) {
    return new NegativeMediaProvider() {
      @Override
      public List<WatchlistEntry> search(WatchlistQuery query) {
        return List.of();
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public boolean authoritative() {
        return authoritative;
      }
    };
  }
}
