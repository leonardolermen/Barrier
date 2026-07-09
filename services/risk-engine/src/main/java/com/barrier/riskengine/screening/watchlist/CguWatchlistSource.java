package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Fonte base das listas da CGU (Portal da Transparência), publicadas como ZIP contendo um CSV
 * ({@code ISO-8859-1}, delimitado por {@code ;}, campos entre aspas). Baixa o pacote do dia,
 * extrai o CSV e mapeia cada linha para um {@link WatchlistRecord} de sanção por documento.
 *
 * <p>As colunas são resolvidas pelo cabeçalho (tolerante a variações de rótulo entre CEIS/CNEP),
 * então não dependem da ordem. Concretas: {@link CeisWatchlistSource}, {@link CnepWatchlistSource}.
 */
abstract class CguWatchlistSource implements WatchlistSource {

  private static final Logger log = LoggerFactory.getLogger(CguWatchlistSource.class);

  private final RestClient client;

  protected CguWatchlistSource(RestClient client) {
    this.client = client;
  }

  /** Segmento do caminho no portal (ex.: {@code ceis}, {@code cnep}). */
  protected abstract String pathSegment();

  /** Data de referência do pacote, no formato {@code yyyyMMdd}. */
  protected abstract String referenceDate();

  @Override
  public WatchlistBatch fetch() {
    log.info("CGU {}: baixando pacote /download-de-dados/{}/{}", source(), pathSegment(), referenceDate());
    byte[] zip =
        client
            .get()
            .uri("/download-de-dados/{seg}/{date}", pathSegment(), referenceDate())
            .retrieve()
            .body(byte[].class);
    if (zip == null || zip.length == 0) {
      throw new IllegalStateException("Pacote " + source() + " vazio");
    }
    List<WatchlistRecord> records = parse(readCsv(zip));
    log.info("CGU {}: {} bytes baixados, {} registro(s) parseado(s)", source(), zip.length, records.size());
    return new WatchlistBatch(pathSegment() + "-" + referenceDate(), records);
  }

  private String readCsv(byte[] zip) {
    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.getName().toLowerCase().endsWith(".csv")) {
          return new String(zis.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Falha ao ler o ZIP da " + source(), e);
    }
    throw new IllegalStateException("Nenhum CSV no pacote " + source());
  }

  private List<WatchlistRecord> parse(String csv) {
    String[] lines = csv.split("\\r?\\n");
    if (lines.length < 2) {
      return List.of();
    }
    List<String> header = CsvSupport.split(lines[0], ';');
    int docCol = column(header, h -> h.contains("CPF OU CNPJ"));
    int nameCol =
        firstColumn(
            header,
            h -> h.contains("NOME INFORMADO"),
            h -> h.contains("RAZAO SOCIAL"),
            h -> h.contains("NOME DO SANCIONADO"),
            h -> h.contains("NOME"));
    int detailCol = firstColumn(header, h -> h.contains("SANCAO"), h -> h.contains("CATEGORIA"));

    if (docCol < 0 || nameCol < 0) {
      throw new IllegalStateException("Cabeçalho inesperado no CSV da " + source());
    }

    List<WatchlistRecord> records = new ArrayList<>();
    for (int i = 1; i < lines.length; i++) {
      if (lines[i].isBlank()) {
        continue;
      }
      List<String> f = CsvSupport.split(lines[i], ';');
      String document = CsvSupport.digitsOnly(at(f, docCol));
      String name = at(f, nameCol);
      if (name == null || name.isBlank()) {
        continue;
      }
      String detail = detailCol >= 0 ? at(f, detailCol) : source();
      records.add(new WatchlistRecord(source(), MatchType.SANCTION, document, name, detail));
    }
    return records;
  }

  private static String at(List<String> fields, int index) {
    return index >= 0 && index < fields.size() ? fields.get(index) : null;
  }

  @SafeVarargs
  private static int firstColumn(List<String> header, Predicate<String>... matchers) {
    for (Predicate<String> matcher : matchers) {
      int idx = column(header, matcher);
      if (idx >= 0) {
        return idx;
      }
    }
    return -1;
  }

  private static int column(List<String> header, Predicate<String> matcher) {
    for (int i = 0; i < header.size(); i++) {
      if (matcher.test(NameNormalizer.normalize(header.get(i)))) {
        return i;
      }
    }
    return -1;
  }
}
