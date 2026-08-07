package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OFAC SDN — lista de sancionados do Tesouro dos EUA. Ingere a lista principal ({@code sdn.csv})
 * e os apelidos ({@code alt.csv}). Cada nome vira uma entrada para o match por nome (fuzzy).
 *
 * <p>Além do nome, a SDN traz no campo <i>remarks</i> o documento nacional (ex.:
 * {@code Tax ID No. 42987643000110 (Brazil)}). Quando é um CPF/CNPJ do Brasil, ele é extraído e
 * indexado como {@code document} — habilitando o <b>match exato por documento</b>, muito mais
 * confiável que o nome (a razão social na lista costuma diferir do nome informado).
 *
 * <p>CSV sem cabeçalho, separado por vírgula, {@code "-0-"} para vazio. Habilitado por
 * {@code barrier.watchlist.ofac.enabled=true} (desligado por padrão).
 */
@Component
@ConditionalOnProperty("barrier.watchlist.ofac.enabled")
class OfacWatchlistSource implements WatchlistSource {

  private static final Logger log = LoggerFactory.getLogger(OfacWatchlistSource.class);
  private static final String SOURCE = "OFAC";
  private static final int SDN_NAME_COLUMN = 1;
  private static final int SDN_REMARKS_COLUMN = 11;
  private static final int ALT_NAME_COLUMN = 3;

  /**
   * Tax ID brasileiro no remarks: {@code Tax ID No. <numero> (Brazil)}. Na lista real a maioria
   * vem formatada ({@code 238.624.338-97}, {@code 11.791.301/0001-05}), só uma minoria em
   * dígitos crus ({@code 42987643000110}) — o grupo captura ambos, os dígitos são extraídos e
   * validados (11 ou 14) depois.
   */
  private static final Pattern BRAZIL_TAX_ID =
      Pattern.compile("Tax ID No\\.\\s*([\\d./-]{11,18})\\s*\\(Brazil\\)");

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
    return SOURCE;
  }

  @Override
  public java.util.Set<MatchType> provides() {
    return java.util.Set.of(MatchType.SANCTION);
  }

  @Override
  public WatchlistBatch fetch() {
    log.info("OFAC: baixando lista SDN ({}) e apelidos ({})", sdnPath, altPath);
    List<WatchlistRecord> sdn = parseSdn(download(sdnPath));
    List<WatchlistRecord> alt = parseAlt(download(altPath));
    long withDoc = sdn.stream().filter(r -> r.document() != null).count();
    log.info(
        "OFAC: {} nomes (SDN, {} com CPF/CNPJ) + {} apelidos (alt) = {} entradas",
        sdn.size(),
        withDoc,
        alt.size(),
        sdn.size() + alt.size());
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

  /** Lista principal: nome (col 1) + documento BR extraído do remarks (col 11), quando houver. */
  static List<WatchlistRecord> parseSdn(String csv) {
    List<WatchlistRecord> records = new ArrayList<>();
    for (String line : csv.split("\\r?\\n")) {
      List<String> fields = row(line, SDN_NAME_COLUMN);
      if (fields == null) {
        continue;
      }
      String name = fields.get(SDN_NAME_COLUMN);
      String document = brazilTaxId(at(fields, SDN_REMARKS_COLUMN));
      records.add(new WatchlistRecord(SOURCE, MatchType.SANCTION, document, name, "OFAC SDN"));
    }
    return records;
  }

  /** Apelidos (aka): nome (col 3), sem documento. */
  static List<WatchlistRecord> parseAlt(String csv) {
    List<WatchlistRecord> records = new ArrayList<>();
    for (String line : csv.split("\\r?\\n")) {
      List<String> fields = row(line, ALT_NAME_COLUMN);
      if (fields == null) {
        continue;
      }
      String name = fields.get(ALT_NAME_COLUMN);
      records.add(new WatchlistRecord(SOURCE, MatchType.SANCTION, null, name, "OFAC aka"));
    }
    return records;
  }

  /** Divide a linha e valida que a coluna do nome existe e não é vazia ({@code -0-}). */
  private static List<String> row(String line, int nameColumn) {
    if (line.isBlank()) {
      return null;
    }
    List<String> fields = CsvSupport.split(line, ',');
    if (nameColumn >= fields.size()) {
      return null;
    }
    String name = fields.get(nameColumn);
    return name.isBlank() || "-0-".equals(name) ? null : fields;
  }

  private static String brazilTaxId(String remarks) {
    if (remarks == null) {
      return null;
    }
    Matcher m = BRAZIL_TAX_ID.matcher(remarks);
    if (!m.find()) {
      return null;
    }
    String digits = CsvSupport.digitsOnly(m.group(1));
    return digits != null && (digits.length() == 11 || digits.length() == 14) ? digits : null;
  }

  private static String at(List<String> fields, int index) {
    return index < fields.size() ? fields.get(index) : null;
  }
}
