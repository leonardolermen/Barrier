package com.barrier.riskengine.screening.service;

import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.WatchlistProvider;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.screening.repository.ScreeningResultRepository;
import com.barrier.riskengine.screening.rule.ScreeningContext;
import com.barrier.riskengine.screening.rule.ScreeningRule;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Consulta as listas restritivas (Gateway) e aplica as regras de match em cadeia (Strategy),
 * agregando os apontamentos num {@link ScreeningResult}.
 */
@Service
public class ScreeningService {

  private final List<WatchlistProvider> providers;
  private final List<ScreeningRule> rules;
  private final ScreeningResultRepository repository;

  public ScreeningService(
      List<WatchlistProvider> providers,
      List<ScreeningRule> rules,
      ScreeningResultRepository repository) {
    this.providers = providers;
    this.rules = rules;
    this.repository = repository;
  }

  public ScreeningResult screen(ScreeningCommand command) {
    WatchlistQuery query =
        new WatchlistQuery(command.documentType(), command.documentDigits(), command.name());

    List<WatchlistEntry> entries =
        providers.stream().flatMap(p -> p.search(query).stream()).toList();

    ScreeningContext context = new ScreeningContext(query, entries);
    List<ScreeningHit> hits =
        rules.stream().flatMap(rule -> rule.evaluate(context).stream()).toList();

    return repository.save(ScreeningResult.of(command.assessmentId(), hits));
  }
}
