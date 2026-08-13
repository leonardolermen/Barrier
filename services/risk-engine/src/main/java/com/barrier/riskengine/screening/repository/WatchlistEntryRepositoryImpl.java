package com.barrier.riskengine.screening.repository;

import com.barrier.riskengine.screening.domain.WatchlistDelta;
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
          + " imported_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
  private static final int BATCH_SIZE = 1000;

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
