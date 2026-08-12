package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.barrier.riskengine.screening.watchlist.interfaces.WatchlistSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Fonte de exemplo que lê uma lista-semente empacotada (CSV em {@code resources/watchlists/}).
 * Serve para exercitar todo o pipeline de forma determinística. Uma fonte real (CGU/OFAC) é
 * outra implementação de {@link WatchlistSource} que baixa e parseia o arquivo publicado.
 *
 * <p>CSV: {@code type,document,name,detail} (com cabeçalho). Todos os registros ficam sob a
 * fonte {@link #source()}, garantindo importação idempotente por fonte.
 */
@Component
public class SeedFileWatchlistSource implements WatchlistSource {

  private static final String RESOURCE = "watchlists/ceis-seed.csv";
  private static final String VERSION = "seed-v1";

  @Override
  public String source() {
    return "SEED";
  }

  /** O CSV-semente tem coluna {@code type} e traz exemplos das duas categorias. */
  @Override
  public Set<MatchType> provides() {
    return Set.of(MatchType.SANCTION, MatchType.PEP);
  }

  @Override
  public WatchlistBatch fetch() {
    List<WatchlistRecord> records = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      boolean header = true;
      while ((line = reader.readLine()) != null) {
        if (header) {
          header = false;
          continue;
        }
        if (line.isBlank()) {
          continue;
        }
        String[] f = line.split(",", -1);
        String document = f[1].isBlank() ? null : f[1].trim();
        records.add(
            new WatchlistRecord(
                source(), MatchType.valueOf(f[0].trim()), document, f[2].trim(), f[3].trim()));
      }
    } catch (IOException e) {
      throw new IllegalStateException("Falha ao ler a lista-semente " + RESOURCE, e);
    }
    return new WatchlistBatch(VERSION, records);
  }
}
