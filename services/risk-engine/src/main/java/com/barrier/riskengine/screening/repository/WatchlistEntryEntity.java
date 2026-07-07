package com.barrier.riskengine.screening.repository;

import com.barrier.riskengine.screening.domain.MatchType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento JPA de uma entrada de lista restritiva ingerida. */
@Entity
@Table(name = "watchlist_entries")
class WatchlistEntryEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "source", nullable = false, length = 40)
  private String source;

  @Enumerated(EnumType.STRING)
  @Column(name = "entry_type", nullable = false, length = 20)
  private MatchType entryType;

  @Column(name = "document", length = 20)
  private String document;

  @Column(name = "name", nullable = false, length = 300)
  private String name;

  @Column(name = "detail", length = 400)
  private String detail;

  @Column(name = "list_version", nullable = false, length = 40)
  private String listVersion;

  @Column(name = "imported_at", nullable = false)
  private Instant importedAt;

  protected WatchlistEntryEntity() {
    // JPA
  }

  WatchlistEntryEntity(
      UUID id,
      String source,
      MatchType entryType,
      String document,
      String name,
      String detail,
      String listVersion,
      Instant importedAt) {
    this.id = id;
    this.source = source;
    this.entryType = entryType;
    this.document = document;
    this.name = name;
    this.detail = detail;
    this.listVersion = listVersion;
    this.importedAt = importedAt;
  }

  MatchType getEntryType() {
    return entryType;
  }

  String getSource() {
    return source;
  }

  String getDocument() {
    return document;
  }

  String getName() {
    return name;
  }

  String getDetail() {
    return detail;
  }
}
