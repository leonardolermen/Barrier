package com.barrier.riskengine.screening.watchlist;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.screening.domain.enums.MatchType;
import java.util.List;
import java.util.Set;

import com.barrier.riskengine.screening.watchlist.interfaces.WatchlistSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class WatchlistReadinessGuardTest {

  private static final WatchlistSource SEED =
      fakeSource("SEED", Set.of(MatchType.SANCTION, MatchType.PEP));
  private static final WatchlistSource OFAC = fakeSource("OFAC", Set.of(MatchType.SANCTION));
  private static final WatchlistSource CEIS = fakeSource("CEIS", Set.of(MatchType.SANCTION));
  private static final WatchlistSource PEP = fakeSource("PEP", Set.of(MatchType.PEP));

  @Test
  void falhaAoSubirEmProducaoComApenasSeedAtiva() {
    assertThatThrownBy(() -> new WatchlistReadinessGuard(List.of(SEED), prod()).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("apenas a SEED");
  }

  /**
   * Regressão do gap que passou despercebido por muito tempo: CEIS, CNEP e OFAC são todas fontes
   * de sanção. Com todas habilitadas e nenhuma de PEP, a PepRiskRule ficava inerte e a exigência
   * de EDD da Circular 3.978 parecia coberta sem estar.
   */
  @Test
  void falhaAoSubirEmProducaoSemNenhumaFonteDePep() {
    assertThatThrownBy(() -> new WatchlistReadinessGuard(List.of(SEED, CEIS, OFAC), prod()).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PEP");
  }

  @Test
  void falhaAoSubirEmProducaoSemNenhumaFonteDeSancao() {
    assertThatThrownBy(() -> new WatchlistReadinessGuard(List.of(SEED, PEP), prod()).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SANCTION");
  }

  @Test
  void naoFalhaEmProducaoComCoberturaDeSancaoEPep() {
    assertThatCode(() -> new WatchlistReadinessGuard(List.of(SEED, CEIS, OFAC, PEP), prod()).run(null))
        .doesNotThrowAnyException();
  }

  /** A SEED não conta para cobertura, mesmo declarando as duas categorias. */
  @Test
  void seedNaoSatisfazACoberturaObrigatoria() {
    assertThatThrownBy(() -> new WatchlistReadinessGuard(List.of(SEED, CEIS), prod()).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PEP");
  }

  @Test
  void naoFalhaForaDeProducaoMesmoComApenasSeedAtiva() {
    MockEnvironment dev = new MockEnvironment();
    dev.setActiveProfiles("dev");

    assertThatCode(() -> new WatchlistReadinessGuard(List.of(SEED), dev).run(null))
        .doesNotThrowAnyException();
  }

  private static MockEnvironment prod() {
    MockEnvironment prod = new MockEnvironment();
    prod.setActiveProfiles("prod");
    return prod;
  }

  private static WatchlistSource fakeSource(String name, Set<MatchType> provides) {
    return new WatchlistSource() {
      @Override
      public String source() {
        return name;
      }

      @Override
      public WatchlistBatch fetch() {
        return new WatchlistBatch(name, List.of());
      }

      @Override
      public Set<MatchType> provides() {
        return provides;
      }
    };
  }
}
