package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.WatchlistProvider;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.domain.MatchBasis;
import com.barrier.riskengine.screening.repository.WatchlistEntryRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Provider que casa contra as listas restritivas ingeridas na base local (match exato por
 * documento). O match por nome (OFAC/ONU) fica para uma fase seguinte.
 */
@Component
public class LocalWatchlistProvider implements WatchlistProvider {

  private final WatchlistEntryRepository repository;

  public LocalWatchlistProvider(WatchlistEntryRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<WatchlistEntry> search(WatchlistQuery query) {
    return repository.findByDocument(query.documentDigits()).stream()
        .map(r -> new WatchlistEntry(r.type(), MatchBasis.DOCUMENT, r.source(), r.name(), r.detail()))
        .toList();
  }

  @Override
  public String name() {
    return "local-watchlist";
  }
}
