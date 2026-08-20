package com.barrier.riskengine.screening.repository;

import com.barrier.commons.name.NameNormalizer;
import com.barrier.riskengine.screening.domain.WatchlistDelta;
import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.barrier.riskengine.screening.repository.interfaces.WatchlistEntryJpaRepository;
import com.barrier.riskengine.screening.repository.interfaces.WatchlistEntryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class WatchlistEntryRepositoryImpl implements WatchlistEntryRepository {

  /**
   * Insert em lote via JDBC. Usar {@code saveAll} da JPA aqui seria lento: como o {@code @Id} é
   * um UUID atribuído, o Spring Data trata cada entidade como "não-nova" e faz merge (um SELECT
   * antes de cada INSERT) — inviável para dezenas de milhar de linhas (OFAC/CGU).
   */
  private static final String INSERT =
      "INSERT INTO watchlist_entries"
          + " (id, source, entry_type, document, document_partial, name, detail, list_version,"
          + " imported_at, name_normalized)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
  private static final int BATCH_SIZE = 1000;

  /**
   * Candidatos por trigrama, com um predicado {@code <%} POR TOKEN.
   *
   * <p><b>UNION e não OR, e o benchmark é que mostrou.</b> Duas versões anteriores ficaram MAIS
   * LENTAS que o {@code findAll()} que vieram substituir — ~940ms contra ~350ms sobre 100 mil
   * entradas:
   *
   * <ol>
   *   <li>{@code EXISTS (SELECT 1 FROM unnest(...))}: dentro de um {@code EXISTS} correlacionado o
   *       planner não empurra a condição para o índice — varre a tabela e avalia a subconsulta
   *       linha a linha;
   *   <li>predicados {@code OR} um a um: com <b>um</b> token o plano era {@code BitmapOr} sobre o
   *       GIN (2,5ms); com <b>três</b>, o planner estimou que três bitmap scans custam mais que um
   *       {@code Seq Scan} e escolheu varrer — avaliando {@code <%} em cada uma das 100 mil linhas,
   *       três vezes. {@code Rows Removed by Filter: 100002}.
   * </ol>
   *
   * <p>Com {@code UNION}, cada ramo é uma consulta independente com um predicado só, e cada uma usa
   * seu índice. É o mesmo resultado lógico com plano determinístico — não depende de o estimador
   * concordar que o índice vale a pena.
   *
   * <p>A lição vale além desta consulta: <b>"usa índice" não é propriedade do SQL, é decisão do
   * planner</b>, e ela muda com o número de predicados e o tamanho da tabela. Só o EXPLAIN sobre
   * volume real responde.
   *
   * <p>SQL montado dinamicamente pelo número de tokens: os placeholders são {@code ?}, e os valores
   * vão por parâmetro — não há concatenação de dado do chamador na consulta.
   */
  private static String candidatesSql(int tokenCount) {
    String colunas = "SELECT source, entry_type, document, document_partial, name, detail";
    StringBuilder sql = new StringBuilder();
    for (int i = 0; i < tokenCount; i++) {
      sql.append(colunas)
          .append(" FROM watchlist_entries WHERE ? <% name_normalized\nUNION\n");
    }
    sql.append(colunas).append(" FROM watchlist_entries WHERE name_normalized IS NULL");
    return sql.toString();
  }

  /** Chaves naturais da versão atual de uma fonte, para calcular o delta da próxima importação. */
  private static final String NATURAL_KEYS =
      "SELECT source || '|~|' || entry_type || '|~|' || coalesce(document, '')"
          + " || '|~|' || coalesce(document_partial, '') || '|~|' || coalesce(name, '')"
          + " FROM watchlist_entries WHERE source = ?";

  /**
   * Separador da chave natural, o mesmo no SQL e em Java. Sequência que não ocorre em nome nem em
   * documento: concatenar direto faria documento "1" + nome "23" colidir com documento "12" + nome
   * "3", e a colisão esconderia uma entrada nova.
   */
  private static final String SEPARATOR = "|~|";

  private final WatchlistEntryJpaRepository jpa;
  private final JdbcTemplate jdbc;

  WatchlistEntryRepositoryImpl(WatchlistEntryJpaRepository jpa, JdbcTemplate jdbc) {
    this.jpa = jpa;
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public WatchlistDelta replaceSource(String source, String version, List<WatchlistRecord> records) {
    // Chaves da versão anterior, lidas antes do DELETE: é o único instante em que dá para saber o
    // que a nova importação acrescenta. Só a chave natural vem para a memória, não a linha —
    // dezenas de milhar de Strings curtas, não dezenas de milhar de entidades.
    Set<String> previous = new HashSet<>(jdbc.queryForList(NATURAL_KEYS, String.class, source));

    jpa.deleteBySource(source);
    OffsetDateTime importedAt = Instant.now().atOffset(ZoneOffset.UTC);
    jdbc.batchUpdate(
        INSERT,
        records,
        BATCH_SIZE,
        (ps, r) -> {
          ps.setObject(1, UUID.randomUUID());
          ps.setString(2, r.source());
          ps.setString(3, r.type().name());
          ps.setString(4, r.document());
          ps.setString(5, r.documentPartial());
          ps.setString(6, r.name());
          ps.setString(7, r.detail());
          ps.setString(8, version);
          ps.setObject(9, importedAt);
          // Normalizado aqui, pelo NameNormalizer, e não em SQL: normalização em dois lugares
          // diverge, e a divergência apareceria como candidato não encontrado — falso negativo
          // silencioso, que é o único erro sem conserto num controle de sanções.
          ps.setString(10, NameNormalizer.normalize(r.name()));
        });

    if (previous.isEmpty()) {
      return WatchlistDelta.firstLoad();
    }
    return WatchlistDelta.of(
        records.stream().filter(r -> !previous.contains(naturalKey(r))).toList());
  }

  /**
   * Identidade de uma entrada para fins de comparação entre importações.
   *
   * <p>Não é o {@code id} (aleatório a cada carga, já que a base é substituída inteira) nem a linha
   * toda: {@code detail} muda de redação sem que a entrada seja outra, e trocar o texto da
   * descrição não é fato novo sobre um cliente. Documento e nome são o que a decide.
   */
  private static String naturalKey(WatchlistRecord r) {
    return String.join(
        SEPARATOR,
        r.source(),
        r.type().name(),
        nullToEmpty(r.document()),
        nullToEmpty(r.documentPartial()),
        nullToEmpty(r.name()));
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  @Override
  public List<WatchlistRecord> findByDocument(String document) {
    if (document == null || document.isBlank()) {
      return List.of();
    }
    return jpa.findByDocument(document).stream().map(WatchlistEntryRepositoryImpl::toRecord).toList();
  }

  @Override
  public List<WatchlistRecord> findNameEntries() {
    return jpa.findAll().stream().map(WatchlistEntryRepositoryImpl::toRecord).toList();
  }

  /**
   * Candidatos a match por nome, vindos do índice de trigramas (V048).
   *
   * <p><b>Blocking, não decisão.</b> Esta consulta só reduz o conjunto que o
   * {@code FuzzyNameWatchlistProvider} vai comparar; quem decide continua sendo a cobertura token a
   * token com Jaro-Winkler, inalterada. Por isso o limiar de trigrama é <b>deliberadamente mais
   * frouxo</b> que o de decisão: o custo de um candidato a mais é uma comparação em memória, e o de
   * um candidato a menos é um sancionado não encontrado.
   *
   * <p>{@code word_similarity} ({@code <%}) e não {@code similarity} ({@code %}): a pergunta é "algum
   * token desta entrada é parecido com o token que procuro", não "as duas strings inteiras se
   * parecem". Similaridade de string inteira erraria em "SILVA, JOSE ANTONIO" contra "JOSE ANTONIO
   * DA SILVA" quando os nomes têm tamanhos diferentes, que é o caso normal entre lista e cadastro.
   *
   * <p>Linhas com {@code name_normalized IS NULL} entram sempre: é o fail-open que mantém o
   * comportamento antigo enquanto a coluna não estiver preenchida (ver V048).
   */
  @Override
  @Transactional(readOnly = true)
  public List<WatchlistRecord> findNameCandidates(Set<String> tokens, double blockingThreshold) {
    if (tokens.isEmpty()) {
      return List.of();
    }
    // O limiar de word_similarity é uma GUC de sessão. `SET LOCAL` a limita à transação corrente,
    // então não vaza para outra consulta que use <% na mesma conexão do pool. É executado como
    // statement separado de propósito: dentro da consulta, o planner avaliaria o set_config depois
    // de escolher o plano, e o índice seria consultado com o limiar errado.
    jdbc.execute("SET LOCAL pg_trgm.word_similarity_threshold = " + blockingThreshold);
    return jdbc.query(
        candidatesSql(tokens.size()),
        (rs, rowNum) ->
            new WatchlistRecord(
                rs.getString("source"),
                MatchType.valueOf(rs.getString("entry_type")),
                rs.getString("document"),
                rs.getString("document_partial"),
                rs.getString("name"),
                rs.getString("detail")),
        tokens.toArray());
  }

  @Override
  public java.util.Map<String, String> sourceVersions() {
    java.util.Map<String, String> versions = new java.util.LinkedHashMap<>();
    jdbc.query(
        "SELECT source, max(list_version) AS list_version FROM watchlist_entries GROUP BY source ORDER BY source",
        rs -> {
          versions.put(rs.getString("source"), rs.getString("list_version"));
        });
    return versions;
  }

  private static WatchlistRecord toRecord(WatchlistEntryEntity e) {
    return new WatchlistRecord(
        e.getSource(),
        e.getEntryType(),
        e.getDocument(),
        e.getDocumentPartial(),
        e.getName(),
        e.getDetail());
  }
}
