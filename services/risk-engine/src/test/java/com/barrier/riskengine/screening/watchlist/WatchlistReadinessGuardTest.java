package com.barrier.riskengine.screening.watchlist;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class WatchlistReadinessGuardTest {

  private static final WatchlistSource SEED = fakeSource("SEED");
  private static final WatchlistSource OFAC = fakeSource("OFAC");

  @Test
  void falhaAoSubirEmProducaoComApenasSeedAtiva() {
    MockEnvironment prod = new MockEnvironment().withProperty("spring.profiles.active", "prod");
    prod.setActiveProfiles("prod");
    WatchlistReadinessGuard guard = new WatchlistReadinessGuard(List.of(SEED), prod);

    assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void naoFalhaEmProducaoQuandoOutraFonteAlemDaSeedEstaAtiva() {
    MockEnvironment prod = new MockEnvironment();
    prod.setActiveProfiles("prod");
    WatchlistReadinessGuard guard = new WatchlistReadinessGuard(List.of(SEED, OFAC), prod);

    assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
  }

  @Test
  void naoFalhaForaDeProducaoMesmoComApenasSeedAtiva() {
    MockEnvironment dev = new MockEnvironment();
    dev.setActiveProfiles("dev");
    WatchlistReadinessGuard guard = new WatchlistReadinessGuard(List.of(SEED), dev);

    assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
  }

  private static WatchlistSource fakeSource(String name) {
    return new WatchlistSource() {
      @Override
      public String source() {
        return name;
      }

      @Override
      public WatchlistBatch fetch() {
        return new WatchlistBatch(name, List.of());
      }
    };
  }
}
