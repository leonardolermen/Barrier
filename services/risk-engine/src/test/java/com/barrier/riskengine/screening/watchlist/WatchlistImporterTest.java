package com.barrier.riskengine.screening.watchlist;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import com.barrier.riskengine.screening.repository.WatchlistEntryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WatchlistImporterTest {

  @Mock WatchlistSource source;
  @Mock WatchlistEntryRepository repository;

  @Test
  void importaSubstituindoAFonte() {
    when(source.source()).thenReturn("SEED");
    when(source.fetch())
        .thenReturn(
            new WatchlistBatch(
                "v1",
                List.of(new WatchlistRecord("SEED", MatchType.SANCTION, "1", "N", "d"))));

    new WatchlistImporter(List.of(source), repository).importAll();

    verify(repository).replaceSource(eq("SEED"), eq("v1"), anyList());
  }

  @Test
  void falhaDeUmaFonteNaoLancaNemGrava() {
    when(source.source()).thenReturn("SEED");
    when(source.fetch()).thenThrow(new RuntimeException("download falhou"));

    new WatchlistImporter(List.of(source), repository).importAll();

    verify(repository, never()).replaceSource(eq("SEED"), org.mockito.ArgumentMatchers.any(), anyList());
  }
}
