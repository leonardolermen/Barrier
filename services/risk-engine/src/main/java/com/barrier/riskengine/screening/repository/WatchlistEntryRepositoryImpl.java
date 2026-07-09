package com.barrier.riskengine.screening.repository;

import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
          + " (id, source, entry_type, document, name, detail, list_version, imported_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
  private static final int BATCH_SIZE = 1000;

  private final WatchlistEntryJpaRepository jpa;
  private final JdbcTemplate jdbc;

  WatchlistEntryRepositoryImpl(WatchlistEntryJpaRepository jpa, JdbcTemplate jdbc) {
    this.jpa = jpa;
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void replaceSource(String source, String version, List<WatchlistRecord> records) {
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
          ps.setString(5, r.name());
          ps.setString(6, r.detail());
          ps.setString(7, version);
          ps.setObject(8, importedAt);
        });
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
    return jpa.findByDocumentIsNull().stream().map(WatchlistEntryRepositoryImpl::toRecord).toList();
  }

  private static WatchlistRecord toRecord(WatchlistEntryEntity e) {
    return new WatchlistRecord(
        e.getSource(), e.getEntryType(), e.getDocument(), e.getName(), e.getDetail());
  }
}
