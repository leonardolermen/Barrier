package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OFAC SDN — lista de sancionados do Tesouro dos EUA, por <b>nome</b> (não há documento
 * BR). Ingere a lista principal ({@code sdn.csv}) e os apelidos ({@code alt.csv}) como entradas
 * sem documento; o {@link FuzzyNameWatchlistProvider} faz o match fuzzy contra elas.
 *
 * <p>Arquivos CSV sem cabeçalho, separados por vírgula, com {@code "-0-"} para vazio. Habilitado
 * por {@code barrier.watchlist.ofac.enabled=true} (desligado por padrão).
 */
@Component
@ConditionalOnProperty("barrier.watchlist.ofac.enabled")
class OfacWatchlistSource implements WatchlistSource {

  private static final Logger log = LoggerFactory.getLogger(OfacWatchlistSource.class);
  private static final int SDN_NAME_COLUMN = 1;
  private static final int ALT_NAME_COLUMN = 3;

  private final RestClient client;
  private final String sdnPath;
  private final String altPath;

  OfacWatchlistSource(
      @Qualifier("ofacRestClient") RestClient client,
      @Value("${barrier.watchlist.ofac.sdn-path:/sdn.csv}") String sdnPath,
      @Value("${barrier.watchlist.ofac.alt-path:/alt.csv}") String altPath) {
    this.client = client;
    this.sdnPath = sdnPath;
    this.altPath = altPath;
  }

  @Override
  public String source() {
    return "OFAC";
  }

  @Override
  public WatchlistBatch fetch() {
    log.info("OFAC: baixando lista SDN ({}) e apelidos ({})", sdnPath, altPath);
    List<WatchlistRecord> sdn = names(download(sdnPath), SDN_NAME_COLUMN, "OFAC SDN");
    List<WatchlistRecord> alt = names(download(altPath), ALT_NAME_COLUMN, "OFAC aka");
    log.info("OFAC: {} nomes (SDN) + {} apelidos (alt) = {} entradas", sdn.size(), alt.size(), sdn.size() + alt.size());
    List<WatchlistRecord> records = new ArrayList<>(sdn.size() + alt.size());
    records.addAll(sdn);
    records.addAll(alt);
    return new WatchlistBatch("ofac-" + java.time.LocalDate.now(), records);
  }

  private String download(String path) {
    byte[] body = client.get().uri(path).retrieve().body(byte[].class);
    if (body == null) {
      throw new IllegalStateException("Download OFAC vazio: " + path);
    }
    return new String(body, StandardCharsets.ISO_8859_1);
  }

  private List<WatchlistRecord> names(String csv, int nameColumn, String detail) {
    List<WatchlistRecord> records = new ArrayList<>();
    for (String line : csv.split("\\r?\\n")) {
      if (line.isBlank()) {
        continue;
      }
      List<String> fields = CsvSupport.split(line, ',');
      if (nameColumn >= fields.size()) {
        continue;
      }
      String name = fields.get(nameColumn);
      if (name == null || name.isBlank() || "-0-".equals(name)) {
        continue;
      }
      records.add(new WatchlistRecord(source(), MatchType.SANCTION, null, name, detail));
    }
    return records;
  }
}
