package com.barrier.riskengine.screening.watchlist;

import com.barrier.commons.name.NameNormalizer;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.barrier.riskengine.screening.watchlist.interfaces.WatchlistSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Fonte base das listas da CGU (Portal da Transparência), publicadas como ZIP contendo um CSV
 * ({@code ISO-8859-1}, delimitado por {@code ;}, campos entre aspas). Baixa o pacote do dia,
 * extrai o CSV e mapeia cada linha para um {@link WatchlistRecord} de sanção por documento.
 *
 * <p>As colunas são resolvidas pelo cabeçalho (tolerante a variações de rótulo entre CEIS/CNEP),
 * então não dependem da ordem. Concretas: {@link CeisWatchlistSource}, {@link CnepWatchlistSource}.
 *
 * <p><b>Retenção do portal:</b> verificado contra o serviço real, o pacote do dia corrente
 * costuma não estar disponível ainda (publicação acontece ao longo do dia) e pacotes de dias
 * mais antigos somem do bucket — só o do dia anterior é garantido. Por isso {@link #fetch()}
 * tenta a data de referência e recua um dia de cada vez ({@value #MAX_LOOKBACK_DAYS} tentativas)
 * até achar um pacote disponível.
 */
abstract class CguWatchlistSource implements WatchlistSource {

  private static final Logger log = LoggerFactory.getLogger(CguWatchlistSource.class);
  private static final int MAX_LOOKBACK_DAYS = 3;

  private final RestClient client;

  protected CguWatchlistSource(RestClient client) {
    this.client = client;
  }

  /** Segmento do caminho no portal (ex.: {@code ceis}, {@code cnep}, {@code pep}). */
  protected abstract String pathSegment();

  /** Data de referência do pacote, no formato {@code yyyyMMdd}. */
  protected abstract String referenceDate();

  /** Categoria dos registros desta lista. As de sanção diferem da de PEP no desfecho de risco. */
  protected MatchType matchType() {
    return MatchType.SANCTION;
  }

  @Override
  public Set<MatchType> provides() {
    return Set.of(matchType());
  }

  /** Rótulos aceitos para a coluna de documento, em ordem de preferência. */
  @SuppressWarnings("unchecked")
  protected Predicate<String>[] documentColumns() {
    return new Predicate[] {(Predicate<String>) h -> h.contains("CPF OU CNPJ")};
  }

  /** Rótulos aceitos para a coluna de nome, em ordem de preferência. */
  @SuppressWarnings("unchecked")
  protected Predicate<String>[] nameColumns() {
    return new Predicate[] {
      (Predicate<String>) h -> h.contains("NOME INFORMADO"),
      (Predicate<String>) h -> h.contains("RAZAO SOCIAL"),
      (Predicate<String>) h -> h.contains("NOME DO SANCIONADO"),
      (Predicate<String>) h -> h.contains("NOME")
    };
  }

  /** Rótulos aceitos para a coluna de detalhe, em ordem de preferência. */
  @SuppressWarnings("unchecked")
  protected Predicate<String>[] detailColumns() {
    return new Predicate[] {
      (Predicate<String>) h -> h.contains("SANCAO"), (Predicate<String>) h -> h.contains("CATEGORIA")
    };
  }

  @Override
  public WatchlistBatch fetch() {
    LocalDate date = LocalDate.parse(referenceDate(), DateTimeFormatter.BASIC_ISO_DATE);
    RuntimeException lastFailure = null;
    for (int attempt = 0; attempt < MAX_LOOKBACK_DAYS; attempt++) {
      String candidate = date.minusDays(attempt).format(DateTimeFormatter.BASIC_ISO_DATE);
      try {
        return download(candidate);
      } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.NotFound e) {
        log.warn(
            "CGU {}: pacote de {} indisponível ({}), tentando o dia anterior",
            source(),
            candidate,
            e.getStatusCode());
        lastFailure = e;
      }
    }
    throw new IllegalStateException(
        "Nenhum pacote " + source() + " disponível nos últimos " + MAX_LOOKBACK_DAYS + " dias",
        lastFailure);
  }

  private WatchlistBatch download(String date) {
    log.info("CGU {}: baixando pacote /download-de-dados/{}/{}", source(), pathSegment(), date);
    byte[] zip =
        client
            .get()
            .uri("/download-de-dados/{seg}/{date}", pathSegment(), date)
            .retrieve()
            .body(byte[].class);
    if (zip == null || zip.length == 0) {
      throw new IllegalStateException("Pacote " + source() + " vazio");
    }
    List<WatchlistRecord> records = parse(readCsv(zip));
    log.info("CGU {}: {} bytes baixados, {} registro(s) parseado(s)", source(), zip.length, records.size());
    return new WatchlistBatch(pathSegment() + "-" + date, records);
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
    int docCol = firstColumn(header, documentColumns());
    int nameCol = firstColumn(header, nameColumns());
    int detailCol = firstColumn(header, detailColumns());

    if (docCol < 0 || nameCol < 0) {
      throw new IllegalStateException("Cabeçalho inesperado no CSV da " + source());
    }

    List<WatchlistRecord> records = new ArrayList<>();
    for (int i = 1; i < lines.length; i++) {
      if (lines[i].isBlank()) {
        continue;
      }
      List<String> f = CsvSupport.split(lines[i], ';');
      String name = at(f, nameCol);
      if (name == null || name.isBlank()) {
        continue;
      }
      String published = at(f, docCol);
      // Documento completo vira chave de match exato; mascarado vira só discriminador do match
      // por nome. Guardar um documento parcial em `document` faria o LocalWatchlistProvider
      // casar contra o CPF errado.
      String document = MaskedCpf.isComplete(published) ? CsvSupport.digitsOnly(published) : null;
      String documentPartial = document == null ? MaskedCpf.parsePublished(published) : null;
      String detail = detailCol >= 0 ? at(f, detailCol) : source();
      records.add(
          new WatchlistRecord(source(), matchType(), document, documentPartial, name, detail));
    }
    return records;
  }

  private static String at(List<String> fields, int index) {
    return index >= 0 && index < fields.size() ? fields.get(index) : null;
  }

  private static int firstColumn(List<String> header, Predicate<String>[] matchers) {
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
