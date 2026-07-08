package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.WatchlistProvider;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import com.barrier.riskengine.screening.repository.WatchlistEntryRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provider de match por <b>nome</b> (fuzzy) contra as listas sem documento (OFAC/ONU e apelidos).
 * Normaliza o nome consultado e cada entrada e mede a similaridade Jaro-Winkler; acima do limiar
 * vira apontamento. Os apelidos (aka) das listas são ingeridos como entradas próprias, então são
 * cobertos naturalmente.
 *
 * <p>Escopo/pragmatismo: carrega as entradas por nome a cada consulta. Para volumes grandes
 * (OFAC ~ dezenas de milhar) a evolução é um índice/blocking; suficiente para o corte atual.
 * O limiar e o tamanho mínimo do nome são configuráveis para calibrar falsos positivos.
 */
@Component
public class FuzzyNameWatchlistProvider implements WatchlistProvider {

  private final WatchlistEntryRepository repository;
  private final double threshold;
  private final int minNameLength;

  public FuzzyNameWatchlistProvider(
      WatchlistEntryRepository repository,
      @Value("${barrier.screening.fuzzy.threshold:0.90}") double threshold,
      @Value("${barrier.screening.fuzzy.min-name-length:6}") int minNameLength) {
    this.repository = repository;
    this.threshold = threshold;
    this.minNameLength = minNameLength;
  }

  @Override
  public List<WatchlistEntry> search(WatchlistQuery query) {
    String normalizedQuery = NameNormalizer.normalize(query.name());
    if (normalizedQuery.length() < minNameLength) {
      return List.of();
    }

    return repository.findNameEntries().stream()
        .map(record -> scored(normalizedQuery, record))
        .filter(m -> m != null)
        .toList();
  }

  private WatchlistEntry scored(String normalizedQuery, WatchlistRecord record) {
    double score = JaroWinkler.similarity(normalizedQuery, NameNormalizer.normalize(record.name()));
    if (score < threshold) {
      return null;
    }
    String detail =
        String.format(
            "match por nome %.0f%% — %s", score * 100, nullSafe(record.detail()));
    return new WatchlistEntry(record.type(), record.source(), record.name(), detail);
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }

  @Override
  public String name() {
    return "fuzzy-name-watchlist";
  }
}
