package com.barrier.riskengine.screening.repository;

import com.barrier.riskengine.screening.domain.enums.ScreeningStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Mapeamento JPA do resultado de screening; os apontamentos ficam serializados em JSON. */
@Entity
@Table(name = "screening_results")
@Getter(AccessLevel.PACKAGE)
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScreeningResultEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "assessment_id", nullable = false, length = 64)
  private String assessmentId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ScreeningStatus status;

  /** JSONB: um cliente com muitos apontamentos estourava o teto (ver migration V026). */
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "hits_json", nullable = false)
  private String hitsJson;

  /** Fonte → versão da lista consultada; snapshot que torna o CLEAR verificável (V028). */
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "sources_json")
  private String sourcesJson;

  @Column(name = "checked_at", nullable = false)
  private Instant checkedAt;
}
