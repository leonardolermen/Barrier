package com.barrier.riskengine.screening.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import com.barrier.riskengine.screening.repository.interfaces.WatchlistEntryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LocalWatchlistProviderTest {

  @Mock WatchlistEntryRepository repository;

  private WatchlistQuery query(String digits) {
    return new WatchlistQuery("CNPJ", digits, "Empresa X");
  }

  @Test
  void documentoNaListaViraHit() {
    when(repository.findByDocument("11444777000161"))
        .thenReturn(
            List.of(
                new WatchlistRecord(
                    "CEIS", MatchType.SANCTION, "11444777000161", "EMPRESA", "inidônea")));

    List<WatchlistEntry> hits = new LocalWatchlistProvider(repository).search(query("11444777000161"));

    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).type()).isEqualTo(MatchType.SANCTION);
    assertThat(hits.get(0).source()).isEqualTo("CEIS");
  }

  @Test
  void documentoForaDaListaNaoGeraHit() {
    when(repository.findByDocument("00000000000000")).thenReturn(List.of());

    assertThat(new LocalWatchlistProvider(repository).search(query("00000000000000"))).isEmpty();
  }
}
