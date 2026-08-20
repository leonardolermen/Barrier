package com.barrier.riskengine.screening.watchlist;

import com.barrier.commons.name.NameNormalizer;
import com.barrier.commons.name.NameTokens;
import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.interfaces.WatchlistProvider;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.domain.enums.MatchBasis;
import com.barrier.riskengine.screening.domain.ScreenedParty;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import com.barrier.riskengine.screening.repository.interfaces.WatchlistEntryRepository;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provider de match por <b>nome</b> contra as entradas das listas restritivas (OFAC/ONU, apelidos,
 * e também as que têm documento — uma empresa sancionada casa por CNPJ e por razão social).
 *
 * <p><b>A comparação é token a token e simétrica</b> ({@link NameTokens}), não Jaro-Winkler sobre a
 * string inteira. A diferença não é de calibragem, é de cobertura: o Jaro-Winkler premia prefixos
 * iguais, e as listas de sanção publicam o nome como {@code SOBRENOME, Nome Meio}. Comparando as
 * strings inteiras, "JOSE ANTONIO DA SILVA" contra a entrada "SILVA, JOSE ANTONIO" — a mesma pessoa
 * — ficava perto de 0.5, muito abaixo de qualquer limiar útil. Somava-se a isso um limiar de 0.95,
 * calibrado olhando só falso positivo. O resultado era um controle que rodava, produzia evidência de
 * que rodou, e não encontrava quase ninguém.
 *
 * <p>Simétrica porque no screening não existe lado "oficial": a entrada da lista pode ter mais ou
 * menos tokens que o cadastro, e basta que um dos nomes cubra o outro. A assimetria de custo é o
 * oposto da verificação de cadastro — um falso positivo custa minutos de analista, um falso negativo
 * é relacionamento com sancionado. Por isso o desfecho de um match por nome é
 * {@link MatchBasis#NAME}, que a {@code SanctionRiskRule} converte em revisão humana, nunca em
 * bloqueio automático.
 *
 * <p>Escopo/pragmatismo: carrega as entradas por nome a cada consulta. Para volumes grandes
 * (OFAC ~ dezenas de milhar) a evolução é um índice/blocking; suficiente para o corte atual. Os
 * tokens do nome consultado são calculados uma vez, fora do laço.
 */
@Component
public class FuzzyNameWatchlistProvider implements WatchlistProvider {

  private static final Logger log = LoggerFactory.getLogger(FuzzyNameWatchlistProvider.class);

  private final WatchlistEntryRepository repository;
  private final double threshold;
  private final int minNameLength;

  private final double blockingThreshold;

  public FuzzyNameWatchlistProvider(
      WatchlistEntryRepository repository,
      @Value("${barrier.screening.fuzzy.threshold:0.90}") double threshold,
      @Value("${barrier.screening.fuzzy.min-name-length:6}") int minNameLength,
      @Value("${barrier.screening.fuzzy.blocking-threshold:0.45}") double blockingThreshold) {
    this.repository = repository;
    this.threshold = threshold;
    this.minNameLength = minNameLength;
    this.blockingThreshold = blockingThreshold;
  }

  /**
   * Tokens de todas as partes consultadas, para uma única busca de candidatos.
   *
   * <p>Mesma razão de {@code searchAll} existir: uma PJ com dez sócios faria dez idas ao banco, e
   * a economia do índice viraria latência de rede.
   */
  private static java.util.Set<String> tokensOf(List<Screened> screened) {
    return screened.stream()
        .flatMap(s -> s.tokens().values().stream())
        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
  }

  @Override
  public List<WatchlistEntry> search(WatchlistQuery query) {
    return searchAll(List.of(query));
  }

  /**
   * Compara <b>todas</b> as partes numa única varredura da base.
   *
   * <p>É o que impede o screening de sócios de multiplicar o custo: uma PJ com 10 sócios geraria 11
   * carregamentos da tabela inteira e 11× a tokenização de cada entrada. Aqui a base é carregada uma
   * vez, cada entrada é tokenizada uma vez, e o resultado é reaproveitado para todas as partes.
   */
  @Override
  public List<WatchlistEntry> searchAll(List<WatchlistQuery> queries) {
    List<Screened> screened =
        queries.stream()
            .map(query -> Screened.of(query, minNameLength))
            .filter(Objects::nonNull)
            .toList();
    if (screened.isEmpty()) {
      return List.of();
    }

    List<WatchlistRecord> candidates = repository.findNameCandidates(tokensOf(screened), blockingThreshold);
    List<WatchlistEntry> matches =
        candidates.stream()
            .flatMap(record -> matchesOf(record, screened))
            .toList();
    // Sem os nomes consultados: é dado pessoal e sairia em toda avaliação. O que interessa para
    // operar é o volume comparado, quantas partes e quantos casaram.
    log.debug(
        "Fuzzy: {} entrada(s) × {} parte(s) comparadas por nome (limiar {}) -> {} match(es)",
        candidates.size(),
        screened.size(),
        threshold,
        matches.size());
    return matches;
  }

  /** Tokeniza a entrada uma única vez e a confronta com cada parte consultada. */
  private Stream<WatchlistEntry> matchesOf(WatchlistRecord record, List<Screened> screened) {
    NameTokens recordTokens = NameTokens.of(record.name());
    if (recordTokens.isEmpty()) {
      return Stream.empty();
    }
    return screened.stream()
        .filter(party -> partialDocumentAllows(party.query(), record))
        .map(party -> scored(party, recordTokens, record))
        .filter(Objects::nonNull);
  }

  /** Consulta já preparada: nome normalizado e tokenizado, feito uma vez por parte. */
  private record Screened(WatchlistQuery query, NameTokens tokens) {

    static Screened of(WatchlistQuery query, int minNameLength) {
      if (NameNormalizer.normalize(query.name()).length() < minNameLength) {
        return null;
      }
      NameTokens tokens = NameTokens.of(query.name());
      return tokens.isEmpty() ? null : new Screened(query, tokens);
    }
  }

  /**
   * Descarta candidatos cujo CPF parcial publicado é incompatível com o documento consultado.
   *
   * <p>Vale para listas que mascaram o documento (PEP da CGU): o nome sozinho casaria com todo
   * homônimo do cadastro, e cada acerto viraria revisão manual. Os 6 dígitos centrais reduzem o
   * espaço de colisão em ~10⁶.
   *
   * <p>Entrada sem documento parcial passa direto — a decisão fica só com o nome, como antes.
   * Consulta de CNPJ contra entrada com CPF parcial nunca é a mesma entidade.
   */
  private boolean partialDocumentAllows(WatchlistQuery query, WatchlistRecord record) {
    if (record.documentPartial() == null) {
      return true;
    }
    String central = MaskedCpf.centralDigitsOf(query.documentDigits());
    return central != null && central.equals(record.documentPartial());
  }

  private WatchlistEntry scored(Screened screened, NameTokens recordTokens, WatchlistRecord record) {
    NameTokens queryTokens = screened.tokens();
    boolean matched =
        queryTokens.coveredBy(recordTokens, threshold) || recordTokens.coveredBy(queryTokens, threshold);
    if (!matched) {
      return null;
    }
    double score =
        Math.max(queryTokens.weakestMatchIn(recordTokens), recordTokens.weakestMatchIn(queryTokens));
    boolean partialConfirmed = record.documentPartial() != null;
    ScreenedParty party = screened.query().party();
    log.info(
        "Fuzzy match {}@{} para {}: '{}' ({}%){}",
        record.type(),
        record.source(),
        party.role(),
        record.name(),
        Math.round(score * 100),
        partialConfirmed ? " [CPF parcial confere]" : "");
    String detail =
        String.format(
            "match por nome %.0f%%%s — %s",
            score * 100,
            partialConfirmed ? " com CPF parcial confirmado" : "",
            nullSafe(record.detail()));
    return new WatchlistEntry(
        record.type(), MatchBasis.NAME, party, record.source(), record.name(), detail);
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }

  @Override
  public String name() {
    return "fuzzy-name-watchlist";
  }
}
