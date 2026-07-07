package com.barrier.riskengine.screening.repository;

import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class WatchlistEntryRepositoryImpl implements WatchlistEntryRepository {

  private final WatchlistEntryJpaRepository jpa;

  WatchlistEntryRepositoryImpl(WatchlistEntryJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  @Transactional
  public void replaceSource(String source, String version, List<WatchlistRecord> records) {
    jpa.deleteBySource(source);
    Instant now = Instant.now();
    List<WatchlistEntryEntity> entities =
        records.stream()
            .map(
                r ->
                    new WatchlistEntryEntity(
                        UUID.randomUUID(),
                        r.source(),
                        r.type(),
                        r.document(),
                        r.name(),
                        r.detail(),
                        version,
                        now))
            .toList();
    jpa.saveAll(entities);
  }

  @Override
  public List<WatchlistRecord> findByDocument(String document) {
    if (document == null || document.isBlank()) {
      return List.of();
    }
    return jpa.findByDocument(document).stream()
        .map(
            e ->
                new WatchlistRecord(
                    e.getSource(), e.getEntryType(), e.getDocument(), e.getName(), e.getDetail()))
        .toList();
  }
}
