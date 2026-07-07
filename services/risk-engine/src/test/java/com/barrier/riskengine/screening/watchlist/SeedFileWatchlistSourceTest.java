package com.barrier.riskengine.screening.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.screening.domain.MatchType;
import org.junit.jupiter.api.Test;

class SeedFileWatchlistSourceTest {

  private final SeedFileWatchlistSource source = new SeedFileWatchlistSource();

  @Test
  void leASementeECarimbaAFonte() {
    WatchlistBatch batch = source.fetch();

    assertThat(source.source()).isEqualTo("SEED");
    assertThat(batch.version()).isNotBlank();
    assertThat(batch.records()).isNotEmpty().allSatisfy(r -> assertThat(r.source()).isEqualTo("SEED"));
    assertThat(batch.records())
        .anySatisfy(
            r -> {
              assertThat(r.document()).isEqualTo("11444777000161");
              assertThat(r.type()).isEqualTo(MatchType.SANCTION);
            });
  }
}
