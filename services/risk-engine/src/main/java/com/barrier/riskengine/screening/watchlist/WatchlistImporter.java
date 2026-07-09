package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.repository.WatchlistEntryRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Importa as listas restritivas para a base local (ver ADR-0010).
 *
 * <p>Roda na subida da aplicação e periodicamente. Isola falhas por fonte: uma lista que falha
 * ao baixar/parsear não impede a importação das demais. A substituição é por fonte (delete +
 * insert), então a base sempre reflete a última importação bem-sucedida de cada uma.
 */
@Component
public class WatchlistImporter implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(WatchlistImporter.class);

  private final List<WatchlistSource> sources;
  private final WatchlistEntryRepository repository;

  public WatchlistImporter(List<WatchlistSource> sources, WatchlistEntryRepository repository) {
    this.sources = sources;
    this.repository = repository;
  }

  @Override
  public void run(ApplicationArguments args) {
    importAll();
  }

  /** Atualização periódica (padrão: diariamente às 03:00). */
  @Scheduled(cron = "${barrier.watchlist.refresh-cron:0 0 3 * * *}")
  public void scheduledRefresh() {
    importAll();
  }

  public void importAll() {
    log.info(
        "Importando watchlists de {} fonte(s) ativa(s): {}",
        sources.size(),
        sources.stream().map(WatchlistSource::source).toList());
    for (WatchlistSource source : sources) {
      try {
        WatchlistBatch batch = source.fetch();
        repository.replaceSource(source.source(), batch.version(), batch.records());
        log.info(
            "Watchlist {} importada: {} entradas (versão {})",
            source.source(),
            batch.records().size(),
            batch.version());
      } catch (RuntimeException e) {
        log.error("Falha ao importar a watchlist {}; mantendo a versão anterior", source.source(), e);
      }
    }
  }
}
