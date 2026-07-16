package com.barrier.riskengine.screening.media;

import com.barrier.riskengine.screening.client.NegativeMediaProvider;
import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.domain.MatchType;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provider de mídia negativa para dev/testes: casa o nome da consulta contra uma lista fixa de
 * nomes sinalizados (CSV em {@code barrier.negative-media.flagged-names}, vazia por padrão —
 * sem falso positivo em dev). Substituir por BigBoost/LexisNexis/Dow Jones em produção, atrás
 * da mesma interface {@link NegativeMediaProvider}.
 */
@Component
public class StubNegativeMediaProvider implements NegativeMediaProvider {

  private final Set<String> flaggedNames;

  public StubNegativeMediaProvider(
      @Value("${barrier.negative-media.flagged-names:}") Set<String> flaggedNames) {
    this.flaggedNames = flaggedNames;
  }

  @Override
  public List<WatchlistEntry> search(WatchlistQuery query) {
    if (query.name() == null) {
      return List.of();
    }
    String normalized = query.name().trim().toUpperCase();
    boolean flagged =
        flaggedNames.stream().anyMatch(name -> normalized.contains(name.trim().toUpperCase()));
    if (!flagged) {
      return List.of();
    }
    return List.of(
        new WatchlistEntry(
            MatchType.ADVERSE_MEDIA,
            name(),
            query.name(),
            "Nome associado a termos de mídia negativa (lavagem/corrupção/fraude/tráfico/"
                + "terrorismo/pirâmide financeira)"));
  }

  @Override
  public String name() {
    return "stub-negative-media";
  }
}
