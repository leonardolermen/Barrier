package com.barrier.riskengine.screening.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import com.barrier.riskengine.screening.repository.WatchlistEntryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FuzzyNameWatchlistProviderTest {

  @Mock WatchlistEntryRepository repository;

  private FuzzyNameWatchlistProvider provider() {
    return new FuzzyNameWatchlistProvider(repository, 0.90, 6);
  }

  private WatchlistQuery query(String name) {
    return new WatchlistQuery("CPF", "00000000000", name);
  }

  private WatchlistRecord ofac(String name) {
    return new WatchlistRecord("OFAC", MatchType.SANCTION, null, name, "SDN");
  }

  @Test
  void nomeQuaseIgualComAcentoEViraHit() {
    when(repository.findNameEntries()).thenReturn(List.of(ofac("OSAMA BIN LADEN")));

    List<WatchlistEntry> hits = provider().search(query("Osama Bin Láden"));

    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).source()).isEqualTo("OFAC");
    assertThat(hits.get(0).detail()).contains("match por nome");
  }

  @Test
  void nomeDiferenteNaoCasa() {
    when(repository.findNameEntries()).thenReturn(List.of(ofac("OSAMA BIN LADEN")));

    assertThat(provider().search(query("Maria da Silva"))).isEmpty();
  }

  @Test
  void apelidoIngeridoComoEntradaTambemCasa() {
    when(repository.findNameEntries())
        .thenReturn(List.of(ofac("USAMA BIN LADIN"))); // aka grafado diferente

    assertThat(provider().search(query("Usama Bin Ladin"))).hasSize(1);
  }

  @Test
  void nomeCurtoNaoAcionaMatch() {
    // abaixo do min-name-length: evita falso positivo, nem consulta o repositório
    assertThat(provider().search(query("Ana"))).isEmpty();
  }
}
