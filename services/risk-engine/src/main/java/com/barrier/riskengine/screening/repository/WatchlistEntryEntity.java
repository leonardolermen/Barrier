package com.barrier.riskengine.screening.repository;

import com.barrier.riskengine.screening.domain.enums.MatchType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Mapeamento JPA de uma entrada de lista restritiva ingerida.
 *
 * <p>Só leitura: a entrada nasce pronta pelo construtor e nunca é alterada, por isso {@code @Getter}
 * sem {@code @Setter}.
 */
@Entity
@Table(name = "watchlist_entries")
@Getter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class WatchlistEntryEntity {

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

  /** Dígitos centrais do CPF quando a fonte publica o documento mascarado (ex.: PEP da CGU). */
  @Column(name = "document_partial", length = 20)
  private String documentPartial;

  @Column(name = "name", nullable = false, length = 300)
  private String name;

  @Column(name = "detail", length = 400)
  private String detail;

  @Column(name = "list_version", nullable = false, length = 40)
  private String listVersion;

  @Column(name = "imported_at", nullable = false)
  private Instant importedAt;

}
