package com.barrier.riskengine.screening.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.domain.MatchType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StubNegativeMediaProviderTest {

  @Test
  void semNomesSinalizadosNuncaCasa() {
    var provider = new StubNegativeMediaProvider(Set.of());

    var hits = provider.search(new WatchlistQuery("CPF", "11144477735", "Fulano de Tal"));

    assertThat(hits).isEmpty();
  }

  @Test
  void nomeSinalizadoGeraApontamentoDeMidiaNegativa() {
    var provider = new StubNegativeMediaProvider(Set.of("FULANO DE TAL"));

    var hits = provider.search(new WatchlistQuery("CPF", "11144477735", "Fulano de Tal"));

    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).type()).isEqualTo(MatchType.ADVERSE_MEDIA);
  }

  @Test
  void nomeNaoSinalizadoNaoCasa() {
    var provider = new StubNegativeMediaProvider(Set.of("OUTRA PESSOA"));

    var hits = provider.search(new WatchlistQuery("CPF", "11144477735", "Fulano de Tal"));

    assertThat(hits).isEmpty();
  }

  @Test
  void nomeNuloNaoCasa() {
    var provider = new StubNegativeMediaProvider(Set.of("FULANO"));

    assertThat(provider.search(new WatchlistQuery("CPF", "11144477735", null))).isEmpty();
  }
}
