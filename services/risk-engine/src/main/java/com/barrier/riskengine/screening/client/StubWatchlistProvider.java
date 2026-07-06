package com.barrier.riskengine.screening.client;

import com.barrier.riskengine.screening.domain.MatchType;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Provider stub com uma pequena lista em memória para desenvolvimento. Será substituído pelas
 * integrações reais (OFAC/ONU/CGU/PEP). O match é por dígitos do documento.
 */
@Component
public class StubWatchlistProvider implements WatchlistProvider {

  // Documentos "conhecidos" apenas para dev/demonstração.
  private static final Map<String, List<WatchlistEntry>> SEED =
      Map.of(
          "52998224725",
          List.of(
              new WatchlistEntry(
                  MatchType.PEP, "base-pep", "Fulano PEP", "Cargo público (exemplo)")),
          "11444777000161",
          List.of(
              new WatchlistEntry(
                  MatchType.SANCTION, "OFAC", "Empresa Sancionada", "SDN List (exemplo)")));

  @Override
  public List<WatchlistEntry> search(WatchlistQuery query) {
    return SEED.getOrDefault(query.documentDigits(), List.of());
  }

  @Override
  public String name() {
    return "stub-watchlist";
  }
}
