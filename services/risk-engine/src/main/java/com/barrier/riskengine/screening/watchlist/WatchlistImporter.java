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
 * <p>Roda na subida da aplicação e periodicamente. Isola falhas por fonte: uma lista que falha ao
 * baixar/parsear não impede a importação das demais. A substituição é por fonte (delete + insert),
 * então a base sempre reflete a última importação bem-sucedida de cada uma.
 *
 * <p>Duas proteções contra perder cobertura sem perceber:
 *
 * <ul>
 *   <li>o resultado de cada fonte é registrado em {@link WatchlistImportStatus}, de onde o health
 *       check e a regra de cobertura leem — antes, uma falha só existia como linha de log;
 *   <li>uma importação que volta <b>vazia</b> não substitui a base. Um CSV que mudou de layout, ou
 *       um ZIP truncado, apagaria a lista inteira de sanções e o screening passaria a responder
 *       CLEAR para todos.
 * </ul>
 */
@Component
public class WatchlistImporter implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(WatchlistImporter.class);

  private final List<WatchlistSource> sources;
  private final WatchlistEntryRepository repository;
  private final WatchlistImportStatus status;

  public WatchlistImporter(
      List<WatchlistSource> sources,
      WatchlistEntryRepository repository,
      WatchlistImportStatus status) {
    this.sources = sources;
    this.repository = repository;
    this.status = status;
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
      importOne(source);
    }
  }

  private void importOne(WatchlistSource source) {
    try {
      WatchlistBatch batch = source.fetch();
      if (batch.records().isEmpty()) {
        // Substituir por vazio é indistinguível de "a lista deixou de existir" — e o efeito é
        // aprovar todo mundo. Mantém a versão anterior e trata como falha.
        String problem = "importação retornou 0 registros; base anterior mantida";
        status.recordFailure(source, problem);
        log.error("Watchlist {}: {}", source.source(), problem);
        return;
      }
      repository.replaceSource(source.source(), batch.version(), batch.records());
      status.recordSuccess(source, batch.records().size());
      log.info(
          "Watchlist {} importada: {} entradas (versão {})",
          source.source(),
          batch.records().size(),
          batch.version());
    } catch (RuntimeException e) {
      status.recordFailure(source, e.getMessage());
      log.error("Falha ao importar a watchlist {}; mantendo a versão anterior", source.source(), e);
    }
  }
}
