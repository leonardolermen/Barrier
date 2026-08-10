package com.barrier.riskengine.screening.service;

import com.barrier.commons.mask.Documents;
import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.WatchlistProvider;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.screening.repository.ScreeningResultRepository;
import com.barrier.riskengine.screening.repository.WatchlistEntryRepository;
import com.barrier.riskengine.screening.rule.ScreeningContext;
import com.barrier.riskengine.screening.rule.ScreeningRule;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Consulta as listas restritivas (Gateway) e aplica as regras de match em cadeia (Strategy),
 * agregando os apontamentos num {@link ScreeningResult}.
 */
@Service
public class ScreeningService {

  private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);

  private final List<WatchlistProvider> providers;
  private final List<ScreeningRule> rules;
  private final ScreeningResultRepository repository;
  private final WatchlistEntryRepository watchlistEntries;

  public ScreeningService(
      List<WatchlistProvider> providers,
      List<ScreeningRule> rules,
      ScreeningResultRepository repository,
      WatchlistEntryRepository watchlistEntries) {
    this.providers = providers;
    this.rules = rules;
    this.repository = repository;
    this.watchlistEntries = watchlistEntries;
  }

  public ScreeningResult screen(ScreeningCommand command) {
    List<WatchlistQuery> queries = queriesFor(command);

    // searchAll e não um search por parte: quem varre a base inteira por nome faz isso uma vez só,
    // senão o custo de uma PJ seria multiplicado pelo tamanho do quadro societário.
    List<WatchlistEntry> entries =
        providers.stream().flatMap(p -> p.searchAll(queries).stream()).toList();

    ScreeningContext context = new ScreeningContext(queries.getFirst(), entries);
    List<ScreeningHit> hits =
        rules.stream().flatMap(rule -> rule.evaluate(context).stream()).toList();

    if (hits.isEmpty()) {
      log.info(
          "Screening {} {} ({} parte(s)): {} entrada(s) em listas, nenhum apontamento",
          command.documentType(),
          Documents.mask(command.documentDigits()),
          queries.size(),
          entries.size());
    } else {
      log.info(
          "Screening {} {} ({} parte(s)): {} apontamento(s) -> {}",
          command.documentType(),
          Documents.mask(command.documentDigits()),
          queries.size(),
          hits.size(),
          hits.stream().map(h -> h.type() + "@" + h.source() + "/" + h.party().role()).toList());
    }

    // Snapshot de contra qual versão de cada lista este screening rodou. Sem isto, um CLEAR de seis
    // meses atrás é uma afirmação sem lastro: a base foi substituída ~180 vezes desde então.
    return repository.save(
        ScreeningResult.of(command.assessmentId(), hits, watchlistEntries.sourceVersions()));
  }

  /**
   * Titular primeiro, depois as partes relacionadas — sócios do QSA e representante legal.
   *
   * <p>Antes só o titular era consultado: empresa com situação ATIVA, CNAE inócuo e um sócio na SDN
   * saía aprovada automaticamente, porque o sócio nunca era perguntado. É a rota de contorno mais
   * barata do sistema — não exige falsificar nada, só constituir uma PJ limpa.
   *
   * <p>Partes sem nome utilizável são descartadas aqui, e não no provider, para que o log de
   * operação reflita quantas partes foram <b>efetivamente</b> consultadas.
   */
  private static List<WatchlistQuery> queriesFor(ScreeningCommand command) {
    List<WatchlistQuery> queries = new ArrayList<>();
    queries.add(
        new WatchlistQuery(command.documentType(), command.documentDigits(), command.name()));
    command.relatedParties().stream()
        .filter(party -> party.name() != null && !party.name().isBlank())
        .map(party -> WatchlistQuery.of(command.documentType(), party))
        .forEach(queries::add);
    return List.copyOf(queries);
  }
}
